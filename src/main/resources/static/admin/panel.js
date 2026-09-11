'use strict';

const API = '/admin/api';
const $ = (id) => document.getElementById(id);

let session = null;
let current = 'disliked';

// -- transport ---------------------------------------------------------------------------------

async function api(path, options = {}) {
	const response = await fetch(API + path, {
		credentials: 'same-origin',
		headers: options.body ? { 'Content-Type': 'application/json' } : {},
		...options,
	});
	if (response.status === 204) return null;
	const text = await response.text();
	const data = text ? JSON.parse(text) : null;
	if (!response.ok) {
		const error = new Error((data && (data.error || data.required)) || response.statusText);
		error.status = response.status;
		error.code = data && data.error;
		throw error;
	}
	return data;
}

// -- reason prompt -----------------------------------------------------------------------------

function askReason(title, note, required) {
	return new Promise((resolve) => {
		const dialog = $('prompt');
		$('prompt-title').textContent = title;
		$('prompt-note').textContent = note || '';
		$('prompt-input').value = '';
		$('prompt-input').placeholder = required ? 'Required — this goes in the audit log' : 'Optional';
		const done = (value) => { dialog.close(); resolve(value); };
		$('prompt-ok').onclick = () => {
			const value = $('prompt-input').value.trim();
			if (required && value.length < 3) { $('prompt-input').focus(); return; }
			done(value);
		};
		$('prompt-cancel').onclick = () => done(null);
		dialog.showModal();
		$('prompt-input').focus();
	});
}

// -- views -------------------------------------------------------------------------------------

const VIEWS = {
	disliked: {
		title: 'Most disliked',
		hint: 'The report queue. There is no report button — dislikes are the signal.',
		load: () => api('/queues/disliked').then((page) => commentList(page.comments, 'Nothing has been downvoted lately.')),
	},
	recent: {
		title: 'Recent comments',
		hint: 'Everything, newest first. The safety net for what nobody happened to downvote.',
		load: () => api('/queues/recent').then((page) => commentList(page.comments, 'No comments yet.')),
	},
	flagged: {
		title: 'Flagged comments',
		hint: 'A watch term matched. These published normally and their authors were told nothing.',
		load: () => api('/queues/flagged').then((page) => commentList(page.comments, 'Nothing flagged.')),
	},
	blocked: {
		title: 'Blocked by the filter',
		hint: 'User-reported false positives first. Allowlisting one fixes it for everyone, permanently.',
		load: () => api('/filter/blocks').then(blockList),
	},
	'filter-stats': {
		title: 'Filter health',
		hint: 'A language whose block rate is an outlier means a bad list, not a rude userbase.',
		load: () => Promise.all([api('/filter/stats/languages'), api('/filter/stats/rules')])
			.then(([languages, rules]) => filterStats(languages, rules)),
	},
	'ban-evasion': {
		title: 'Suspected ban evasion',
		hint: 'A new signup whose device id matches a banned one. Confirm by behaviour — these ids collide.',
		load: () => api('/queues/ban-evasion').then(banEvasionList),
	},
	brigades: {
		title: 'Rating brigades',
		hint: 'A spike of extreme ratings from new accounts. Nothing was reverted automatically.',
		load: () => api('/queues/brigades').then(brigadeList),
	},
	disputes: {
		title: 'Work disputes',
		hint: 'Two works that may be one, or one that may be two.',
		load: () => api('/queues/disputes').then(disputeList),
	},
	devices: {
		title: 'Banned devices',
		hint: 'Reversal lives here and only here — there is no in-app appeal.',
		admin: true,
		load: () => api('/devices').then(deviceList),
	},
	moderators: {
		title: 'Moderators',
		hint: 'Invite, set a role, disable. An account cannot remove the last admin.',
		admin: true,
		load: () => api('/moderators').then(moderatorList),
	},
	actions: {
		title: 'Audit log',
		hint: 'Append-only. Admins see everything; moderators see their own actions.',
		load: () => api('/actions').then(actionList),
	},
};

function element(html) {
	const template = document.createElement('template');
	template.innerHTML = html.trim();
	return template.content.firstElementChild;
}

function escapeHtml(value) {
	return String(value == null ? '' : value).replace(/[&<>"']/g, (character) => (
		{ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]
	));
}

function when(iso) {
	const date = new Date(iso);
	return date.toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' });
}

function empty(message) {
	return element('<div class="empty">' + escapeHtml(message) + '</div>');
}

function commentList(comments, emptyMessage) {
	if (!comments.length) return empty(emptyMessage);
	const wrapper = document.createElement('div');
	comments.forEach((comment) => wrapper.appendChild(commentCard(comment)));
	return wrapper;
}

function commentCard(comment) {
	const stateTag = comment.state === 'visible' ? '' :
		'<span class="tag ' + comment.state + '">' + comment.state + '</span>';
	const bannedTag = comment.author_banned ? '<span class="tag banned">author banned</span>' : '';
	const shadowTag = comment.author_shadowbanned ? '<span class="tag shadowed">author shadowed</span>' : '';
	const flagTag = comment.flagged_rule
		? '<span class="tag shadowed">flagged: ' + escapeHtml(comment.flagged_rule) + '</span>'
		: '';
	const priors = comment.author_removed_count > 0
		? '<span title="comments of theirs already removed">' + comment.author_removed_count + ' prior removals</span>'
		: '';

	const card = element(
		'<div class="card">' +
			'<div class="meta">' +
				'<strong>' + escapeHtml(comment.author) + '</strong>' +
				'<span class="votes"><span class="up">+' + comment.up + '</span> ' +
					'<span class="down">-' + comment.down + '</span></span>' +
				'<span>' + escapeHtml(comment.work_title) + '</span>' +
				(comment.chapter_id ? '<span>ch. ' + escapeHtml(comment.chapter_id) + '</span>' : '') +
				(comment.lang ? '<span>' + escapeHtml(comment.lang) + '</span>' : '') +
				'<span>' + when(comment.created_at) + '</span>' +
				stateTag + flagTag + bannedTag + shadowTag + priors +
			'</div>' +
			'<p class="body">' + escapeHtml(comment.body || '(removed)') + '</p>' +
			'<div class="actions"></div>' +
		'</div>',
	);

	const actions = card.querySelector('.actions');
	const add = (label, className, handler) => {
		const button = element('<button class="' + className + '">' + label + '</button>');
		button.onclick = () => run(handler, button);
		actions.appendChild(button);
	};

	if (comment.state === 'removed') {
		add('Restore', '', async () => {
			const reason = await askReason('Restore comment', 'Puts the text back from the audit snapshot.', false);
			if (reason === null) return false;
			await api('/comments/' + comment.id + '/restore', { method: 'POST', body: JSON.stringify({ reason }) });
			return true;
		});
	} else {
		add('Remove', 'danger', async () => {
			const reason = await askReason('Remove comment', 'The text is blanked here and kept only in the audit log.', true);
			if (reason === null) return false;
			await api('/comments/' + comment.id + '/remove', { method: 'POST', body: JSON.stringify({ reason }) });
			return true;
		});
	}

	add(comment.author_shadowbanned ? 'Un-shadowban' : 'Shadowban', '', async () => {
		const path = comment.author_shadowbanned ? 'unshadowban' : 'shadowban';
		const reason = await askReason(
			comment.author_shadowbanned ? 'Lift shadowban' : 'Shadowban author',
			'Their comments stay visible to them alone, and their votes stop counting.',
			!comment.author_shadowbanned,
		);
		if (reason === null) return false;
		await api('/users/' + comment.author_id + '/' + path, { method: 'POST', body: JSON.stringify({ reason }) });
		return true;
	});

	if (!comment.author_banned) {
		add('Ban author', 'danger', async () => {
			const reason = await askReason(
				'Ban author',
				'Deletes every comment they have written and removes their read access. Not reversible from the app.',
				true,
			);
			if (reason === null) return false;
			await api('/users/' + comment.author_id + '/ban', { method: 'POST', body: JSON.stringify({ reason }) });
			return true;
		});
		add('Ban device', 'danger', async () => {
			const reason = await askReason('Ban this device', 'Stops the same device signing up again.', true);
			if (reason === null) return false;
			await api('/users/' + comment.author_id + '/ban-device', { method: 'POST', body: JSON.stringify({ reason }) });
			return true;
		});
	} else {
		add('Unban author', '', async () => {
			const reason = await askReason('Lift ban', 'Their deleted comments do not come back.', false);
			if (reason === null) return false;
			await api('/users/' + comment.author_id + '/unban', { method: 'POST', body: JSON.stringify({ reason }) });
			return true;
		});
	}

	add('Reset nickname', '', async () => {
		const reason = await askReason('Reset nickname', 'Back to anon#1234.', false);
		if (reason === null) return false;
		await api('/users/' + comment.author_id + '/reset-nickname', { method: 'POST', body: JSON.stringify({ reason }) });
		return true;
	});

	add('All their comments', '', async () => {
		const page = await api('/users/' + comment.author_id + '/comments');
		show('Comments by ' + comment.author, page.comments.length + ' in total', commentList(page.comments, 'None.'));
		return false;
	});

	return card;
}

function blockList(blocks) {
	if (!blocks.length) return empty('Nothing blocked, or nothing left to review.');
	const wrapper = document.createElement('div');
	blocks.forEach((block) => {
		const card = element(
			'<div class="card"><div class="meta">' +
			'<span class="tag ' + (block.tier === 'severe' ? 'removed' : 'shadowed') + '">' +
			escapeHtml(block.tier) + '</span>' +
			'<strong class="mono">' + escapeHtml(block.term) + '</strong>' +
			(block.disputed ? '<span class="tag removed">user says wrong</span>' : '') +
			'<span>' + escapeHtml(block.surface) + '</span>' +
			(block.lang ? '<span>' + escapeHtml(block.lang) + '</span>' : '') +
			'<span>' + when(block.created_at) + '</span>' +
			'</div>' +
			'<p class="body">' + escapeHtml(block.context || '(the text has been forgotten)') + '</p>' +
			'<div class="actions"></div></div>',
		);
		const actions = card.querySelector('.actions');

		// The one action worth having: it fixes the word for everyone, for good.
		const allow = element('<button class="primary">Allow a word</button>');
		allow.onclick = () => run(async () => {
			const term = await askReason(
				'Allowlist a word',
				'Type the word that should have been allowed - `assassin`, not `ass`. Allowlisting the ' +
					'rule\'s own term switches the rule off entirely.',
				true,
			);
			if (term === null) return false;
			await api('/filter/blocks/' + block.id + '/allow', {
				method: 'POST',
				body: JSON.stringify({ term }),
			});
			return true;
		}, allow);
		actions.appendChild(allow);

		const uphold = element('<button>Correct block</button>');
		uphold.onclick = () => run(async () => {
			await api('/filter/blocks/' + block.id + '/uphold', { method: 'POST', body: JSON.stringify({}) });
			return true;
		}, uphold);
		actions.appendChild(uphold);

		if (block.rule_id) {
			const demote = element('<button class="danger">Demote this rule</button>');
			demote.onclick = () => run(async () => {
				await api('/filter/rules/' + block.rule_id + '/demote', { method: 'POST', body: JSON.stringify({}) });
				return true;
			}, demote);
			actions.appendChild(demote);
		}

		wrapper.appendChild(card);
	});
	return wrapper;
}

function filterStats(languages, rules) {
	const wrapper = document.createElement('div');

	wrapper.appendChild(element('<h3 style="margin-top:0">By language</h3>'));
	const byLanguage = element(
		'<table><thead><tr><th>Language</th><th>Blocks</th><th>Disputed</th><th>Rate</th></tr></thead>' +
		'<tbody></tbody></table>',
	);
	languages.forEach((row) => {
		const rate = row.blocks ? Math.round((row.disputed / row.blocks) * 100) : 0;
		byLanguage.querySelector('tbody').appendChild(element(
			'<tr><td>' + escapeHtml(row.lang || 'unknown') + '</td><td>' + row.blocks + '</td><td>' +
			row.disputed + '</td><td>' + rate + '%</td></tr>',
		));
	});
	wrapper.appendChild(byLanguage);

	wrapper.appendChild(element('<h3>Worst rules</h3>'));
	const byRule = element(
		'<table><thead><tr><th>Term</th><th>Tier</th><th>Language</th><th>Blocks</th><th>Wrong</th>' +
		'<th></th></tr></thead><tbody></tbody></table>',
	);
	rules.forEach((row) => {
		const tr = element(
			'<tr><td class="mono">' + escapeHtml(row.term) + '</td><td>' + escapeHtml(row.tier) +
			'</td><td>' + escapeHtml(row.lang || 'global') + '</td><td>' + row.blocks + '</td><td>' +
			Math.round(row.false_positive_rate * 100) + '%</td><td></td></tr>',
		);
		if (row.rule_id) {
			const demote = element('<button>Demote</button>');
			demote.onclick = () => run(async () => {
				await api('/filter/rules/' + row.rule_id + '/demote', { method: 'POST', body: JSON.stringify({}) });
				return true;
			}, demote);
			tr.lastElementChild.appendChild(demote);
		}
		byRule.querySelector('tbody').appendChild(tr);
	});
	wrapper.appendChild(byRule);

	return wrapper;
}

function banEvasionList(flags) {
	if (!flags.length) return empty('Nothing flagged.');
	const table = element(
		'<table><thead><tr><th>User</th><th>Comments</th><th>Flagged</th><th></th></tr></thead><tbody></tbody></table>',
	);
	const body = table.querySelector('tbody');
	flags.forEach((flag) => {
		const row = element(
			'<tr><td>' + escapeHtml(flag.user) + '<br><span class="mono" style="color:var(--muted);font-size:12px">' +
			escapeHtml(flag.user_id) + '</span></td><td>' + flag.comments + '</td><td>' + when(flag.created_at) +
			'</td><td></td></tr>',
		);
		const cell = row.lastElementChild;
		const review = element('<button>Mark reviewed</button>');
		review.onclick = () => run(async () => {
			const note = await askReason('Mark reviewed', 'A note for the log, if this needs one.', false);
			if (note === null) return false;
			await api('/queues/ban-evasion/' + flag.id + '/review', { method: 'POST', body: JSON.stringify({ reason: note }) });
			return true;
		}, review);
		const ban = element('<button class="danger">Ban user</button>');
		ban.onclick = () => run(async () => {
			const reason = await askReason('Ban user', 'Confirm from behaviour, not from the match alone.', true);
			if (reason === null) return false;
			await api('/users/' + flag.user_id + '/ban', { method: 'POST', body: JSON.stringify({ reason }) });
			return true;
		}, ban);
		cell.append(review, ' ', ban);
		body.appendChild(row);
	});
	return table;
}

function brigadeList(flags) {
	if (!flags.length) return empty('No brigades flagged.');
	const table = element(
		'<table><thead><tr><th>Work</th><th>Recent</th><th>Baseline/day</th><th>Extreme</th><th>New accounts</th>' +
		'<th>Detected</th><th></th></tr></thead><tbody></tbody></table>',
	);
	const body = table.querySelector('tbody');
	flags.forEach((flag) => {
		const percent = (value) => Math.round(value * 100) + '%';
		const row = element(
			'<tr><td>' + escapeHtml(flag.work_title) + '</td><td>' + flag.recent_count + '</td><td>' +
			flag.baseline.toFixed(2) + '</td><td>' + percent(flag.extreme_share) + '</td><td>' +
			percent(flag.new_user_share) + '</td><td>' + when(flag.detected_at) + '</td><td></td></tr>',
		);
		const review = element('<button>Mark reviewed</button>');
		review.onclick = () => run(async () => {
			const note = await askReason('Mark reviewed', '', false);
			if (note === null) return false;
			await api('/queues/brigades/' + flag.id + '/review', { method: 'POST', body: JSON.stringify({ reason: note }) });
			return true;
		}, review);
		row.lastElementChild.appendChild(review);
		body.appendChild(row);
	});
	return table;
}

function disputeList(disputes) {
	if (!disputes.length) return empty('No open disputes.');
	const wrapper = document.createElement('div');
	disputes.forEach((dispute) => {
		const card = element(
			'<div class="card"><div class="meta"><span class="tag">' + escapeHtml(dispute.kind) + '</span>' +
			'<span>' + when(dispute.created_at) + '</span></div>' +
			'<p class="body"><strong>#' + escapeHtml(dispute.work_id) + '</strong> ' + escapeHtml(dispute.work_title) +
			(dispute.other_work_id
				? '<br><strong>#' + escapeHtml(dispute.other_work_id) + '</strong> ' + escapeHtml(dispute.other_work_title || '')
				: '') +
			(dispute.note ? '<br><span style="color:var(--muted)">' + escapeHtml(dispute.note) + '</span>' : '') +
			'</p><div class="actions"></div></div>',
		);
		const actions = card.querySelector('.actions');

		if (session.moderator.role === 'admin' && dispute.other_work_id) {
			const merge = element('<button>Merge into #' + escapeHtml(dispute.work_id) + '</button>');
			merge.onclick = () => run(async () => {
				const reason = await askReason('Merge works', 'Reversible: the losing work becomes a redirect.', true);
				if (reason === null) return false;
				await api('/works/merge', {
					method: 'POST',
					body: JSON.stringify({ from: dispute.other_work_id, into: dispute.work_id, reason }),
				});
				await api('/queues/disputes/' + dispute.id + '/resolve', { method: 'POST', body: JSON.stringify({ reason }) });
				return true;
			}, merge);
			actions.appendChild(merge);

			const unmerge = element('<button>Un-merge #' + escapeHtml(dispute.other_work_id) + '</button>');
			unmerge.onclick = () => run(async () => {
				const reason = await askReason('Undo a merge', 'Content goes back to where it was written.', false);
				if (reason === null) return false;
				await api('/works/' + dispute.other_work_id + '/unmerge', { method: 'POST', body: JSON.stringify({ reason }) });
				return true;
			}, unmerge);
			actions.appendChild(unmerge);
		}

		const resolve = element('<button>Resolve</button>');
		resolve.onclick = () => run(async () => {
			const note = await askReason('Resolve dispute', 'What did you decide?', false);
			if (note === null) return false;
			await api('/queues/disputes/' + dispute.id + '/resolve', { method: 'POST', body: JSON.stringify({ reason: note }) });
			return true;
		}, resolve);
		actions.appendChild(resolve);
		wrapper.appendChild(card);
	});
	return wrapper;
}

function deviceList(devices) {
	if (!devices.length) return empty('No device bans.');
	const table = element(
		'<table><thead><tr><th>Device</th><th>Reason</th><th>Banned</th><th></th></tr></thead><tbody></tbody></table>',
	);
	const body = table.querySelector('tbody');
	devices.forEach((device) => {
		const row = element(
			'<tr><td class="mono">' + escapeHtml(device.fingerprint) + '</td><td>' + escapeHtml(device.reason || '') +
			'</td><td>' + when(device.banned_at) + '</td><td></td></tr>',
		);
		const button = element('<button>Reverse</button>');
		button.onclick = () => run(async () => {
			await api('/devices/' + device.fingerprint + '/unban', { method: 'POST', body: JSON.stringify({}) });
			return true;
		}, button);
		row.lastElementChild.appendChild(button);
		body.appendChild(row);
	});
	return table;
}

function moderatorList(moderators) {
	const wrapper = document.createElement('div');
	const table = element(
		'<table><thead><tr><th>Username</th><th>Role</th><th>2FA</th><th>Last login</th><th></th></tr></thead>' +
		'<tbody></tbody></table>',
	);
	const body = table.querySelector('tbody');
	moderators.forEach((moderator) => {
		const row = element(
			'<tr><td>' + escapeHtml(moderator.username) + (moderator.disabled ? ' <span class="tag">disabled</span>' : '') +
			'</td><td>' + escapeHtml(moderator.role) + '</td><td>' + (moderator.totp_confirmed ? 'yes' : 'not enrolled') +
			'</td><td>' + (moderator.last_login_at ? when(moderator.last_login_at) : '—') + '</td><td></td></tr>',
		);
		const cell = row.lastElementChild;

		const toggle = element('<button>' + (moderator.disabled ? 'Enable' : 'Disable') + '</button>');
		toggle.onclick = () => run(async () => {
			await api('/moderators/' + moderator.id, {
				method: 'PATCH',
				body: JSON.stringify({ disabled: !moderator.disabled }),
			});
			return true;
		}, toggle);

		const promote = element('<button>Make ' + (moderator.role === 'admin' ? 'moderator' : 'admin') + '</button>');
		promote.onclick = () => run(async () => {
			await api('/moderators/' + moderator.id, {
				method: 'PATCH',
				body: JSON.stringify({ role: moderator.role === 'admin' ? 'moderator' : 'admin' }),
			});
			return true;
		}, promote);

		const reset = element('<button>Reset 2FA</button>');
		reset.onclick = () => run(async () => {
			await api('/moderators/' + moderator.id + '/reset-totp', { method: 'POST', body: JSON.stringify({}) });
			return true;
		}, reset);

		cell.append(toggle, ' ', promote, ' ', reset);
		body.appendChild(row);
	});
	wrapper.appendChild(table);

	const invite = element(
		'<div class="card" style="margin-top:16px"><h3 style="margin-top:0">Invite a moderator</h3>' +
		'<label for="inv-u">Username</label><input id="inv-u" autocomplete="off">' +
		'<label for="inv-p">Initial password</label><input id="inv-p" autocomplete="off">' +
		'<label for="inv-r">Role</label><select id="inv-r"><option value="moderator">moderator</option>' +
		'<option value="admin">admin</option></select>' +
		'<div class="row"><button class="primary" id="inv-go">Create</button></div>' +
		'<p style="color:var(--muted);font-size:12px">They enrol their own authenticator at first sign-in.</p></div>',
	);
	wrapper.appendChild(invite);
	invite.querySelector('#inv-go').onclick = () => run(async () => {
		await api('/moderators', {
			method: 'POST',
			body: JSON.stringify({
				username: invite.querySelector('#inv-u').value.trim(),
				password: invite.querySelector('#inv-p').value,
				role: invite.querySelector('#inv-r').value,
			}),
		});
		return true;
	}, invite.querySelector('#inv-go'));

	return wrapper;
}

function actionList(actions) {
	if (!actions.length) return empty('Nothing yet.');
	const table = element(
		'<table><thead><tr><th>When</th><th>Who</th><th>What</th><th>Target</th><th>Reason</th></tr></thead>' +
		'<tbody></tbody></table>',
	);
	const body = table.querySelector('tbody');
	actions.forEach((action) => {
		body.appendChild(element(
			'<tr><td>' + when(action.created_at) + '</td><td>' + escapeHtml(action.moderator) + '</td><td>' +
			escapeHtml(action.action) + '</td><td class="mono">' + escapeHtml(action.target_type) + ' ' +
			escapeHtml(action.target_id) + '</td><td>' + escapeHtml(action.reason || '') + '</td></tr>',
		));
	});
	return table;
}

// -- shell -------------------------------------------------------------------------------------

function show(title, hint, node) {
	$('view-title').textContent = title;
	$('view-hint').textContent = hint || '';
	$('view').replaceChildren(node);
}

async function run(handler, button) {
	button.disabled = true;
	$('view-error').textContent = '';
	try {
		if (await handler()) await render();
	} catch (error) {
		$('view-error').textContent = error.message;
	} finally {
		button.disabled = false;
	}
}

async function render() {
	const view = VIEWS[current];
	$('view-error').textContent = '';
	$('view').replaceChildren(empty('Loading…'));
	try {
		const node = await view.load();
		show(view.title, view.hint, node);
	} catch (error) {
		if (error.status === 401) return start();
		show(view.title, view.hint, empty(error.message));
	}
	refreshCounts();
}

async function refreshCounts() {
	try {
		const { counts } = await api('/queues/counts');
		Object.entries(counts).forEach(([key, value]) => {
			const badge = document.querySelector('[data-count="' + key + '"]');
			if (badge) badge.textContent = value;
		});
	} catch (ignored) { /* the nav badges are decoration; a failure here must not blank the view */ }
}

function buildNav() {
	const queueKeys = ['disliked', 'recent', 'flagged', 'blocked', 'ban-evasion', 'brigades', 'disputes'];
	const adminKeys = ['filter-stats', 'actions', 'devices', 'moderators'];
	const countKeys = {
		disliked: 'disliked',
		flagged: 'flagged',
		blocked: 'blocked',
		'ban-evasion': 'ban_evasion',
		brigades: 'brigades',
		disputes: 'disputes',
	};
	const isAdmin = session.moderator.role === 'admin';

	const build = (container, keys) => {
		container.replaceChildren();
		keys.forEach((key) => {
			const view = VIEWS[key];
			if (view.admin && !isAdmin) return;
			const button = element(
				'<button data-view="' + key + '">' + escapeHtml(view.title) +
				(countKeys[key] ? '<span class="count" data-count="' + countKeys[key] + '">0</span>' : '') +
				'</button>',
			);
			button.onclick = () => {
				current = key;
				document.querySelectorAll('nav button[data-view]').forEach((other) => {
					other.setAttribute('aria-current', String(other === button));
				});
				render();
			};
			if (key === current) button.setAttribute('aria-current', 'true');
			container.appendChild(button);
		});
	};
	build($('nav-queues'), queueKeys);
	build($('nav-admin'), adminKeys);

	$('who-name').textContent = session.moderator.username;
	$('who-role').textContent = session.moderator.role;
}

function screen(name) {
	$('login').hidden = name !== 'login';
	$('enrol').hidden = name !== 'enrol';
	$('app').hidden = name !== 'app';
}

async function enterEnrolment() {
	screen('enrol');
	try {
		const { secret, uri } = await api('/totp/enroll', { method: 'POST', body: JSON.stringify({}) });
		$('enrol-secret').textContent = secret.replace(/(.{4})/g, '$1 ').trim();
		$('enrol-link').href = uri;
	} catch (error) {
		$('enrol-error').textContent = error.message;
	}
}

async function start() {
	try {
		session = await api('/me');
	} catch (error) {
		session = null;
	}
	if (!session) { screen('login'); $('u').focus(); return; }
	if (session.needs_totp_enrolment) return enterEnrolment();
	screen('app');
	buildNav();
	render();
}

$('signin').onclick = async () => {
	$('login-error').textContent = '';
	try {
		session = await api('/login', {
			method: 'POST',
			body: JSON.stringify({ username: $('u').value, password: $('p').value, code: $('c').value || null }),
		});
		$('p').value = '';
		$('c').value = '';
		if (session.needs_totp_enrolment) return enterEnrolment();
		screen('app');
		buildNav();
		render();
	} catch (error) {
		$('login-error').textContent = {
			totp_required: 'That code was wrong or missing.',
			invalid_credentials: 'Wrong username or password.',
			disabled: 'This account has been disabled.',
		}[error.code] || error.message;
	}
};

['u', 'p', 'c'].forEach((id) => {
	$(id).addEventListener('keydown', (event) => { if (event.key === 'Enter') $('signin').click(); });
});

$('enrol-confirm').onclick = async () => {
	$('enrol-error').textContent = '';
	try {
		await api('/totp/confirm', { method: 'POST', body: JSON.stringify({ code: $('enrol-code').value }) });
		start();
	} catch (error) {
		$('enrol-error').textContent = 'That code did not match. Check your phone’s clock and try the next one.';
	}
};

const signOut = async () => {
	await api('/logout', { method: 'POST', body: JSON.stringify({}) }).catch(() => {});
	session = null;
	screen('login');
};
$('signout').onclick = signOut;
$('enrol-cancel').onclick = signOut;

start();
