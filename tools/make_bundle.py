#!/usr/bin/env python3
"""
Builds a transfer-and-start bundle: a compiled image, a filled-in `.env`, and a compose file that
needs neither the source tree nor a JDK on the far end.

Why: the Dockerfile's build stage is a full Gradle run against a JDK image. On a 1-2 GB VPS that is
slow at best and an OOM at worst, and it means the box needs the source and the toolchain for every
upgrade. Building here and shipping the result keeps the server a runtime-only box.

    python tools/make_bundle.py                       # community.kotatsuredo.app
    python tools/make_bundle.py --domain other.example --out dist

The generated `.env` holds real secrets, so the output directory is gitignored and the file is
written 0600. Regenerating produces *new* secrets: run it once, keep what it gives you.
"""
import argparse
import os
import pathlib
import re
import secrets
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def run(*args, **kwargs):
    print("  $", " ".join(str(a) for a in args), flush=True)
    return subprocess.run(args, check=True, cwd=ROOT, **kwargs)


def version_tag() -> str:
    try:
        sha = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout.strip()
        dirty = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout.strip()
        return f"{sha}-dirty" if dirty else sha
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "local"


def default_port() -> int:
    """From Config.kt, so the nginx site and the compose file cannot disagree with the app."""
    config = (ROOT / "src/main/kotlin/io/kotatsuredo/server/Config.kt").read_text(encoding="utf-8")
    return int(re.search(r"DEFAULT_PORT\s*=\s*(\d+)", config).group(1))


def build_env(domain: str, image: str) -> str:
    """Rewrites .env.example rather than templating from scratch, so its comments cannot drift."""
    text = (ROOT / ".env.example").read_text(encoding="utf-8")
    text += (
        "\n# The running image. tools/deploy.sh rewrites this line; editing it by hand and running\n"
        "# `docker compose up -d` is also a perfectly good rollback.\n"
        f"API_IMAGE={image}\n"
    )
    password = secrets.token_urlsafe(24)
    values = {
        "APP_ENV": "production",
        "SERVER_DOMAIN": domain,
        "RULES_URL": f"https://{domain}/rules",
        # One value, two variables: the api and postgres have to agree.
        "POSTGRES_PASSWORD": password,
        "DATABASE_PASSWORD": password,
        # No default anywhere on purpose - see Config.kt. Changing it invalidates every device ban.
        "DEVICE_PEPPER": secrets.token_urlsafe(32),
        "MOD_TOTP_ENCRYPTION_KEY": secrets.token_urlsafe(32),
        "MOD_BOOTSTRAP_USERNAME": "admin",
        "MOD_BOOTSTRAP_PASSWORD": secrets.token_urlsafe(18),
    }
    # token_urlsafe is [A-Za-z0-9_-], which matters: a `$` in a compose env file is interpolated.
    for key, value in values.items():
        pattern = re.compile(rf"^{key}=.*$", re.MULTILINE)
        if pattern.search(text):
            text = pattern.sub(f"{key}={value}", text, count=1)
        else:
            text += f"\n{key}={value}\n"
    return text


def build_compose(image: str, proxy: str, port: int) -> str:
    """Derives the deployment compose file from the real one, so the two cannot drift apart."""
    text = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    migrate_marker = "  migrate:\n    build: .\n"
    assert migrate_marker in text, "docker-compose.yml no longer builds the migrator the way this script expects"
    text = text.replace(
        migrate_marker,
        "  migrate:\n"
        "    # Run the schema migration with the exact same release image as the API.\n"
        f"    image: ${{API_IMAGE:-{image}}}\n",
        1,
    )
    marker = "  api:\n    build: .\n"
    assert marker in text, "docker-compose.yml no longer builds the api the way this script expects"
    text = text.replace(
        marker,
        "  api:\n"
        "    # Prebuilt and loaded from a tarball; the tag lives in .env so that switching versions -\n"
        "    # a deploy, or a rollback - is one line there and a restart, with this file untouched.\n"
        f"    image: ${{API_IMAGE:-{image}}}\n",
        1,
    )
    if proxy == "nginx":
        # Drop Caddy entirely rather than leaving it to fail on a bound port, and publish the api on
        # loopback so the only way in is through the nginx that already owns 80 and 443.
        text = text.replace(
            "# Two containers plus a TLS terminator. That is the whole deployment (PLAN.md §A).",
            "# Two containers. TLS is terminated by the nginx already running on this box - the site\n"
            "# config for it is beside this file.",
            1,
        )
        text = text[: text.index("  caddy:")].rstrip() + "\n"
        expose = f'    expose:\n      - "${{PORT:-{port}}}"\n'
        assert expose in text, "the api service no longer exposes the port this script expects"
        text = text.replace(
            expose,
            "    # Loopback only: nginx reaches it, the internet does not.\n"
            f'    ports:\n      - "127.0.0.1:${{API_HOST_PORT:-{port}}}:${{PORT:-{port}}}"\n',
            1,
        )
        text += "\nvolumes:\n  postgres-data:\n"
        assert "caddy" not in text, "the nginx bundle must not carry a Caddy service"
    else:
        text = text.replace("./deploy/Caddyfile:/etc/caddy/Caddyfile:ro", "./Caddyfile:/etc/caddy/Caddyfile:ro", 1)
    # A line, not a substring: the word "rebuild:" inside a comment is not a build directive.
    assert not re.search(r"^\s*build:", text, re.M), "the bundle must not need a build context"
    return text


def build_nginx_site(domain: str, port: int) -> str:
    template = (ROOT / "deploy" / "nginx" / "site.conf.template").read_text(encoding="utf-8")
    return template.replace("{{DOMAIN}}", domain).replace("{{PORT}}", str(port))


START = """# Start here

Everything in this folder was built elsewhere. The server needs Docker and nothing else - no JDK, no
Gradle, no source tree.

{proxy}

## Then claim the panel, once

Open `https://{domain}/admin` and sign in as **`admin`**. The password is `MOD_BOOTSTRAP_PASSWORD` in
`.env` - that is its only purpose, so read it there rather than copying it around.

You will be asked to enrol an authenticator before you can do anything at all; that is deliberate.
Change the password from the panel, then **delete both `MOD_BOOTSTRAP_*` lines from `.env` and run
`docker compose up -d` again**. The bootstrap path disables itself as soon as any moderator exists,
so leaving them is harmless - but a standing credential in a file is a standing credential.

## `.env` is the only thing here that is not reproducible

It holds freshly generated secrets, and two of them cannot be recovered if lost:

- **`DEVICE_PEPPER`** - changing it invalidates every device ban, permanently.
- **`POSTGRES_PASSWORD`** - it is the database.

Back the file up somewhere that is not this server, alongside wherever the database dumps go.

## Upgrading later

Build a new bundle where the source is, copy over the tarball and the compose file, and **keep the
`.env` you already have**:

```sh
docker compose exec -T postgres pg_dump -U kotatsuredo kotatsuredo | gzip > backup-$(date +%F).sql.gz
docker load < kotatsuredo-server-<new>.tar.gz
docker compose up -d
```

Migrations run at startup, which is why the dump comes first.

Everything else - backups, moderation, what the filter needs watching for - is in `DEPLOY.md` in the
repository.
"""

CADDY_STEPS = """## Before you copy it across

The DNS record has to resolve *first*: Caddy asks Let's Encrypt for a certificate on first boot and
the challenge is answered on port 80 of this box. **Nothing else may be listening on 80 or 443** - if
the box already runs a web server, you want the nginx bundle instead (`--proxy nginx`).

```
{domain}.  A  <this server's IP>
```

**If the zone is on Cloudflare, set it to "DNS only" - the grey cloud.** Proxying it makes Cloudflare
answer the challenge instead of this box, so the certificate never issues, and it puts a third party
in a position to read every comment in plaintext, which the privacy notice says is not the case.

## Copy and start

```sh
scp -r {folder} you@your-server:~/
ssh you@your-server
cd {folder}

docker load < {image_file}
docker compose up -d
curl https://{domain}/v1/health          # {{{{"status":"ok","database":true}}}}
```

`docker load` is what makes the `image:` line in `docker-compose.yml` resolve. There is no build step.
"""

NGINX_STEPS = """## What this bundle assumes

The box already runs nginx on 80 and 443 for something else, so there is no Caddy here. The api binds
**127.0.0.1:{port} only** - nothing about it is reachable from the internet except through the nginx
site config included in this folder.

Check the DNS record resolves to this box first, and that it is **"DNS only"** if the zone is on
Cloudflare - the grey cloud. Proxying it would break the certificate and put a third party in a
position to read every comment in plaintext, which the privacy notice says is not the case.

## Copy and start

```sh
scp -r {folder} you@your-server:~/
ssh you@your-server
cd {folder}

docker load < {image_file}
docker compose up -d
curl http://127.0.0.1:{port}/v1/health    # {{{{"status":"ok","database":true}}}}
```

That last line has to work before nginx can be any use. There is no build step.

## Then put nginx in front of it

```sh
sudo certbot certonly --nginx -d {domain}

sudo cp {domain}.conf /etc/nginx/sites-available/
sudo ln -s /etc/nginx/sites-available/{domain}.conf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

curl https://{domain}/v1/health
```

`certonly` gets the certificate without editing any nginx config, so your existing site is untouched.

### One thing in that file is load-bearing

```
access_log off;
error_log /dev/null crit;
```

That is the entire mechanism behind the privacy notice's claim that an IP address is never stored -
"not in a web-server log, not in the database, not anywhere". nginx logs `$remote_addr` on every
request by default. **If you turn either line back on, the notice stops being true and you have to
rewrite it before you do.** Your other sites are unaffected; this applies only to this server block.
"""




def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--domain", default="community.kotatsuredo.app")
    parser.add_argument("--out", default="dist")
    parser.add_argument("--platform", default="linux/amd64", help="the VPS's architecture, not this machine's")
    parser.add_argument("--skip-build", action="store_true", help="reuse an image already built under this tag")
    parser.add_argument("--no-image", action="store_true", help="write the files only, no tarball - deploy.sh streams the image itself")
    parser.add_argument(
        "--proxy", choices=("caddy", "nginx"), default="caddy",
        help="caddy: the stack owns 80/443. nginx: the box already has a web server, so the api "
             "publishes on loopback and an nginx site config is written for you.",
    )
    args = parser.parse_args()

    tag = version_tag()
    image = f"kotatsuredo-server:{tag}"
    folder = f"kotatsuredo-server-{tag}"
    out = ROOT / args.out / folder
    # Empty it rather than remove it: on Windows anything holding the directory - a shell sitting in
    # it, an indexer - makes rmdir fail, and regenerating a bundle should not depend on that.
    out.mkdir(parents=True, exist_ok=True)
    for entry in out.iterdir():
        shutil.rmtree(entry) if entry.is_dir() else entry.unlink()

    if not args.skip_build:
        print(f"\n== building {image} for {args.platform}")
        # --provenance/--sbom off: buildx otherwise wraps the image in an attestation manifest list,
        # which is dead weight here and which older `docker load` on the far end chokes on.
        run(
            "docker", "build", "--platform", args.platform,
            "--provenance=false", "--sbom=false",
            "-t", image, ".",
        )

    image_file = f"{folder}.tar.gz"
    if args.no_image:
        # deploy.sh streams the image over SSH itself; it only wants the compose file from here.
        print("\n== no tarball (--no-image)")
    else:
        print(f"\n== saving the image to {image_file}")
        with open(out / image_file, "wb") as handle:
            save = subprocess.Popen(["docker", "save", image], stdout=subprocess.PIPE, cwd=ROOT)
            gzip = subprocess.Popen(["gzip", "-1"], stdin=save.stdout, stdout=handle)
            save.stdout.close()
            if gzip.wait() != 0 or save.wait() != 0:
                print("docker save failed", file=sys.stderr)
                return 1

    print("\n== writing the rest of the bundle")
    port = default_port()
    (out / "docker-compose.yml").write_text(
        build_compose(image, args.proxy, port), encoding="utf-8", newline="\n",
    )
    if args.proxy == "caddy":
        shutil.copyfile(ROOT / "deploy" / "Caddyfile", out / "Caddyfile")
    else:
        (out / f"{args.domain}.conf").write_text(
            build_nginx_site(args.domain, port), encoding="utf-8", newline="\n",
        )

    env_path = out / ".env"
    env_path.write_text(build_env(args.domain, image), encoding="utf-8", newline="\n")
    try:
        os.chmod(env_path, 0o600)
    except OSError:
        pass  # Windows; the copy on the server is what matters.

    steps = (CADDY_STEPS if args.proxy == "caddy" else NGINX_STEPS).format(
        domain=args.domain, folder=folder, image_file=image_file, port=port,
    )
    (out / "START.md").write_text(
        START.format(proxy=steps, domain=args.domain, folder=folder, image_file=image_file),
        encoding="utf-8",
        newline="\n",
    )

    print(f"\n== {out}")
    for entry in sorted(out.iterdir()):
        print(f"   {entry.name:<44} {entry.stat().st_size / 1e6:8.1f} MB")
    print("\nCopy that folder to the server and follow START.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
