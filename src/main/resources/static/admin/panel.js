'use strict';

const API = '/admin/api';
const $ = (id) => document.getElementById(id);

let session = null;
let current = 'overview';

// -- transport ---------------------------------------------------------------------------------

async function api(path, options = {}) {
	const headers = new Headers(options.headers || {});
	headers.set('X-KotatsuRedo-CSRF', '1');
	if (options.body) headers.set('Content-Type', 'application/json');
	const response = await fetch(API + path, {
		credentials: 'same-origin',
		...options,
		headers,
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
	overview: {
		title: 'Overview',
		hint: 'What the instance looks like right now, and what is waiting for you.',
		load: () => api('/overview').then(overviewPage),
	},
	health: {
		title: 'Health',
		hint: 'What the server is doing right now. Refusals, queues, and whether anything is piling up.',
		load: () => api('/ops').then(healthPage),
	},
	disliked: {
		title: 'Most disliked',
		hint: 'The report queue. There is no report button — dislikes are the signal. Shadowbanned authors’ newer comments are not listed: nobody can see them.',
		load: () => api('/queues/disliked').then((page) => commentList(page.comments, 'Nothing has been downvoted lately.')),
	},
	recent: {
		title: 'Recent comments',
		hint: 'Everything, newest first, including what is shadowed or removed. The safety net for what nobody happened to downvote.',
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
		hint: 'A spike of extreme ratings against the work’s usual lean, from new accounts. Nothing was reverted automatically.',
		load: () => api('/queues/brigades').then(brigadeList),
	},
	disputes: {
		title: 'Work disputes',
		hint: 'Two works that may be one, or one that may be two.',
		load: () => api('/queues/disputes').then(disputeList),
	},
	sanctions: {
		title: 'Sanctions',
		hint: 'Everyone banned or shadowbanned, and the decision that put them there. Lifting one is done here.',
		load: () => api('/sanctions').then(sanctionList),
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
	account: {
		title: 'Account',
		hint: 'Changing your password verifies both factors and signs out every open session.',
		load: () => Promise.resolve(accountPage()),
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
	// The author's standing, which persists across every view they appear in: a moderator looking at
	// a comment should never have to go and check whether this person is already dealt with.
	const shadowTag = comment.author_shadowbanned
		? '<span class="tag shadowed" title="Their comments are visible only to them">author shadowbanned</span>'
		: '';
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
		// Only where the queue is computed. Elsewhere there is nothing to dismiss it from, and a
		// button that appears to do nothing is worse than no button.
		if (current === 'disliked' || current === 'flagged') {
			add('Ignore', '', async () => {
				const reason = await askReason(
					'Ignore this comment',
					'Leaves the comment exactly as it is and takes it out of this queue.',
					false,
				);
				if (reason === null) return false;
				await api('/comments/' + comment.id + '/dismiss', { method: 'POST', body: JSON.stringify({ reason }) });
				return true;
			});
		}
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
		'<table><thead><tr><th>Work</th><th>Recent</th><th>Baseline/day</th><th>Against lean</th><th>New accounts</th>' +
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

function accountPage() {
	const card = element(
		'<div class="card"><h3 style="margin-top:0">Change password</h3>' +
		'<label>Current password</label><input class="current" type="password" autocomplete="current-password">' +
		'<label>New password</label><input class="next" type="password" autocomplete="new-password">' +
		'<label>Confirm new password</label><input class="confirm" type="password" autocomplete="new-password">' +
		'<label>Authenticator code</label><input class="code" inputmode="numeric" autocomplete="one-time-code">' +
		'<div class="row"><button class="primary change">Change password</button></div>' +
		'<div class="error local-error"></div></div>',
	);
	const button = card.querySelector('.change');
	button.onclick = async () => {
		const errorBox = card.querySelector('.local-error');
		errorBox.textContent = '';
		const newPassword = card.querySelector('.next').value;
		if (newPassword !== card.querySelector('.confirm').value) {
			errorBox.textContent = 'The new passwords do not match.';
			return;
		}
		button.disabled = true;
		try {
			await api('/password', {
				method: 'POST',
				body: JSON.stringify({
					current_password: card.querySelector('.current').value,
					new_password: newPassword,
					code: card.querySelector('.code').value,
				}),
			});
			session = null;
			$('u').value = '';
			$('p').value = '';
			$('c').value = '';
			screen('login');
			$('login-error').textContent = 'Password changed. Sign in again.';
			$('u').focus();
		} catch (error) {
			errorBox.textContent = {
				invalid_current_password: 'The current password was wrong.',
				bad_code: 'That authenticator code was wrong or already used.',
				weak_password: 'Use at least 12 characters for the new password.',
			}[error.code] || error.message;
			button.disabled = false;
		}
	};
	return card;
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
	const queueKeys = ['overview', 'disliked', 'recent', 'flagged', 'blocked', 'ban-evasion', 'brigades', 'disputes'];
	const adminKeys = ['health', 'sanctions', 'filter-stats', 'actions', 'account', 'devices', 'moderators'];
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

/* ---------------------------------------------------------------------------------------------
 * Home.
 *
 * A console, not a document. A moderator opens this to answer one question - is anything wrong -
 * so the page leads with a gauge that can be read from across a desk, then the queues that fill
 * it, then the shape of the last fortnight. Every figure that can be drawn is drawn: a count you
 * have to compare against yesterday in your head is a count doing half its job.
 *
 * All the drawing is hand-rolled SVG. The panel's own CSP is `default-src 'none'`, so there is no
 * charting library to reach for and never will be - which is fine, because a bar chart is thirty
 * lines and a dependency is forever.
 * ------------------------------------------------------------------------------------------- */

const QUEUE_META = {
	disliked: {
		label: 'Most disliked',
		view: 'disliked',
		icon: 'M7 14l5-9 5 9M4 19h16',
	},
	flagged: {
		label: 'Flagged',
		view: 'flagged',
		icon: 'M6 20V4h11l-2 4 2 4H6',
	},
	blocked: {
		label: 'Filter blocks',
		view: 'blocked',
		icon: 'M5 5l14 14M12 4a8 8 0 100 16 8 8 0 000-16z',
	},
	ban_evasion: {
		label: 'Ban evasion',
		view: 'ban-evasion',
		icon: 'M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z',
	},
	brigades: {
		label: 'Rating brigades',
		view: 'brigades',
		icon: 'M4 18l4-6 4 3 4-8 4 5',
	},
	disputes: {
		label: 'Work disputes',
		view: 'disputes',
		icon: 'M8 7h8M8 12h8M8 17h5M4 4v16',
	},
};

function svg(inner, attrs) {
	return '<svg xmlns="http://www.w3.org/2000/svg" ' + attrs + '>' + inner + '</svg>';
}

function icon(path) {
	return svg(
		'<path d="' + path + '" fill="none" stroke="currentColor" stroke-width="1.7" ' +
		'stroke-linecap="round" stroke-linejoin="round"/>',
		'viewBox="0 0 24 24" class="ico" aria-hidden="true"',
	);
}

function overviewPage(data) {
	const page = element('<div class="overview"></div>');
	const waiting = Object.entries(data.queues).filter(([, count]) => count > 0);
	const total = waiting.reduce((sum, [, count]) => sum + count, 0);

	page.appendChild(hero(data, waiting, total));
	if (waiting.length) page.appendChild(queueGrid(waiting));
	page.appendChild(activityPanel(data.series));

	const lower = element('<section class="split"></section>');
	lower.appendChild(filterPanel(data.top_rules));
	lower.appendChild(languagePanel(data.languages));
	page.appendChild(lower);

	page.appendChild(totalsStrip(data.totals));
	return page;
}

/**
 * The gauge is the page's thesis: one arc, one number, readable without reading.
 *
 * Its scale is deliberately soft - twenty items fills it - because the question is "a little or a
 * lot", and a gauge that needs its own axis explained has stopped being a gauge.
 */
function hero(data, waiting, total) {
	const node = element('<section class="hero"></section>');
	const ceiling = 20;
	const fraction = Math.min(1, total / ceiling);
	const radius = 52;
	const circumference = 2 * Math.PI * radius;
	const state = total === 0 ? 'calm' : (total >= 10 ? 'busy' : 'some');

	const ring = element(
		'<div class="gauge ' + state + '">' +
		svg(
			'<circle cx="60" cy="60" r="' + radius + '" class="track"/>' +
			'<circle cx="60" cy="60" r="' + radius + '" class="fill" ' +
			'stroke-dasharray="' + circumference.toFixed(1) + '" ' +
			'stroke-dashoffset="' + (circumference * (1 - fraction)).toFixed(1) + '"/>',
			'viewBox="0 0 120 120" class="gauge-svg" role="img" aria-label="' + total + ' waiting"',
		) +
		'<div class="gauge-mid"><span class="g-n">' + total + '</span>' +
		'<span class="g-l">waiting</span></div>' +
		'</div>',
	);
	node.appendChild(ring);

	const copy = element('<div class="hero-copy"></div>');
	if (total === 0) {
		copy.appendChild(element('<h2 class="say">All clear</h2>'));
		copy.appendChild(element(
			'<p class="because">Nothing is queued. ' + data.today.comments + ' comment' +
			plural(data.today.comments) + ' arrived in the last day.</p>',
		));
	} else {
		const biggest = waiting.slice().sort((a, b) => b[1] - a[1])[0];
		copy.appendChild(element(
			'<h2 class="say">' + total + ' item' + plural(total) + ' to review</h2>',
		));
		copy.appendChild(element(
			'<p class="because">Mostly <strong>' + escapeHtml(labelFor(biggest[0]).toLowerCase()) +
			'</strong>, at ' + biggest[1] + '.</p>',
		));
	}

	const pills = element('<div class="hero-stats"></div>');
	[
		['Comments', data.today.comments],
		['New readers', data.today.users],
		['Ratings', data.today.ratings],
		['Blocked', data.today.blocked],
		['Actions', data.today.actions],
	].forEach(([name, value]) => {
		pills.appendChild(element(
			'<div class="pill"><span class="p-n">' + value + '</span><span class="p-l">' +
			escapeHtml(name) + '</span></div>',
		));
	});
	copy.appendChild(pills);
	copy.appendChild(element('<p class="stamp">last 24 hours</p>'));
	node.appendChild(copy);
	return node;
}

function labelFor(key) {
	return QUEUE_META[key] ? QUEUE_META[key].label : key.replace(/_/g, ' ');
}

function plural(n) {
	return n === 1 ? '' : 's';
}

function goTo(key) {
	current = key;
	document.querySelectorAll('nav button[data-view]').forEach((other) => {
		other.setAttribute('aria-current', String(other.dataset.view === key));
	});
	render();
}

function queueGrid(waiting) {
	const grid = element('<section class="queue-grid"></section>');
	waiting.sort((a, b) => b[1] - a[1]).forEach(([key, count]) => {
		const meta = QUEUE_META[key];
		const tile = element(
			'<button class="q-card">' +
			'<span class="q-ico">' + icon(meta ? meta.icon : 'M5 12h14') + '</span>' +
			'<span class="q-n">' + count + '</span>' +
			'<span class="q-l">' + escapeHtml(labelFor(key)) + '</span>' +
			'</button>',
		);
		if (meta && VIEWS[meta.view]) {
			tile.onclick = () => goTo(meta.view);
		} else {
			tile.disabled = true;
		}
		grid.appendChild(tile);
	});
	return grid;
}

/**
 * Fourteen days, two series, one panel.
 *
 * Two areas on a shared canvas but each scaled to its own peak, with both peaks labelled - signups
 * and comments differ by an order of magnitude, and a shared axis would flatten the smaller one to
 * a flat line while implying the two are comparable.
 */
function activityPanel(series) {
	const node = element('<section class="panel chart-panel"></section>');
	node.appendChild(element(
		'<div class="panel-head"><h3>Activity</h3>' +
		'<div class="legend"><span class="k comments">Comments</span>' +
		'<span class="k readers">New readers</span></div></div>',
	));

	const width = 560;
	const height = 150;
	const pad = 10;
	const comments = series.map((d) => d.comments);
	const readers = series.map((d) => d.users);
	const peakC = Math.max(1, ...comments);
	const peakR = Math.max(1, ...readers);

	const path = (values, peak) => {
		const step = (width - pad * 2) / Math.max(1, values.length - 1);
		return values.map((value, i) => {
			const x = pad + i * step;
			const y = height - pad - (value / peak) * (height - pad * 2);
			return (i === 0 ? 'M' : 'L') + x.toFixed(1) + ' ' + y.toFixed(1);
		}).join(' ');
	};
	const area = (values, peak) => path(values, peak) +
		' L' + (width - pad) + ' ' + (height - pad) + ' L' + pad + ' ' + (height - pad) + ' Z';

	const gridLines = [0.25, 0.5, 0.75].map((f) => {
		const y = (pad + f * (height - pad * 2)).toFixed(1);
		return '<line x1="' + pad + '" x2="' + (width - pad) + '" y1="' + y + '" y2="' + y + '" class="grid"/>';
	}).join('');

	node.appendChild(element(
		svg(
			'<defs>' +
			'<linearGradient id="gc" x1="0" y1="0" x2="0" y2="1">' +
			'<stop offset="0%" class="gc-0"/><stop offset="100%" class="gc-1"/></linearGradient>' +
			'<linearGradient id="gr" x1="0" y1="0" x2="0" y2="1">' +
			'<stop offset="0%" class="gr-0"/><stop offset="100%" class="gr-1"/></linearGradient>' +
			'</defs>' +
			gridLines +
			'<path d="' + area(comments, peakC) + '" class="area-c"/>' +
			'<path d="' + path(comments, peakC) + '" class="line-c"/>' +
			'<path d="' + area(readers, peakR) + '" class="area-r"/>' +
			'<path d="' + path(readers, peakR) + '" class="line-r"/>',
			'viewBox="0 0 ' + width + ' ' + height + '" class="chart" preserveAspectRatio="none" ' +
			'role="img" aria-label="Comments and new readers over fourteen days"',
		),
	));
	node.appendChild(element(
		'<div class="chart-foot"><span>' + escapeHtml(series[0].day.slice(5)) + '</span>' +
		'<span class="peaks">peak ' + peakC + ' comments · ' + peakR + ' readers</span>' +
		'<span>today</span></div>',
	));
	return node;
}

function filterPanel(rules) {
	const node = element('<section class="panel"></section>');
	node.appendChild(element('<div class="panel-head"><h3>Filter load</h3><span class="sub">7 days</span></div>'));
	if (!rules.length) {
		node.appendChild(element('<p class="empty-soft">The filter has not stopped anything this week.</p>'));
		return node;
	}
	const peak = Math.max(...rules.map((r) => r.blocks));
	const list = element('<div class="rows"></div>');
	rules.forEach((rule) => {
		const rate = rule.blocks === 0 ? 0 : Math.round((rule.disputed / rule.blocks) * 100);
		list.appendChild(element(
			'<div class="rowline">' +
			'<span class="rl-k mono">' + escapeHtml(rule.term) + '</span>' +
			'<span class="tier ' + escapeHtml(rule.tier) + '">' + escapeHtml(rule.tier) + '</span>' +
			'<span class="track"><i class="' + (rate >= 30 ? 'hot' : '') + '" style="width:' +
			Math.round((rule.blocks / peak) * 100) + '%"></i></span>' +
			'<span class="rl-v mono">' + rule.blocks + '</span>' +
			'<span class="rl-d mono' + (rate >= 30 ? ' hot' : '') + '">' + rate + '%</span>' +
			'</div>',
		));
	});
	node.appendChild(list);
	node.appendChild(element(
		'<p class="note">Blocks are the bar, disputes the percentage. A rule demotes itself once a ' +
		'third of its blocks are disputed.</p>',
	));
	return node;
}

function languagePanel(languages) {
	const node = element('<section class="panel"></section>');
	node.appendChild(element('<div class="panel-head"><h3>Languages</h3><span class="sub">all time</span></div>'));
	if (!languages.length) {
		node.appendChild(element('<p class="empty-soft">No comments yet.</p>'));
		return node;
	}
	const peak = Math.max(...languages.map((l) => l.comments));
	const list = element('<div class="rows"></div>');
	languages.forEach((row) => {
		const denominator = row.comments + row.blocked;
		const rate = denominator === 0 ? 0 : Math.round((row.blocked / denominator) * 100);
		list.appendChild(element(
			'<div class="rowline">' +
			'<span class="rl-k mono">' + escapeHtml(row.lang) + '</span>' +
			'<span class="track"><i style="width:' + Math.round((row.comments / peak) * 100) + '%"></i></span>' +
			'<span class="rl-v mono">' + row.comments + '</span>' +
			'<span class="rl-d mono' + (rate >= 20 ? ' hot' : '') + '">' + rate + '%</span>' +
			'</div>',
		));
	});
	node.appendChild(list);
	node.appendChild(element(
		'<p class="note">The percentage is how much of that language the filter stopped. An outlier ' +
		'means a bad word list, not a rude userbase.</p>',
	));
	return node;
}

function totalsStrip(totals) {
	const strip = element('<section class="totals"></section>');
	[
		['Readers', totals.users],
		['Comments', totals.comments],
		['Removed', totals.removed],
		['Ratings', totals.ratings],
		['Works', totals.works],
		['Moderators', totals.moderators],
	].forEach(([name, value]) => {
		strip.appendChild(element(
			'<div class="t-cell"><span class="t-n">' + value + '</span><span class="t-l">' +
			escapeHtml(name) + '</span></div>',
		));
	});
	return strip;
}


// -- health ------------------------------------------------------------------------------------

let healthTimer = null;

/**
 * The view that exists because none of this was visible.
 *
 * A day of refused resolutions, a nickname that never reached the server, and a catalogue quietly
 * inventing duplicate works all went unnoticed, because seeing any of them meant an ssh session and
 * a grep - and the logs were gone by the next deploy. The numbers were always there; nothing showed
 * them.
 */
function healthPage(data) {
	const page = element('<div class="overview"></div>');
	const hour = data.last_60m;
	const recent = data.last_5m;

	page.appendChild(healthStrip(data, hour));
	page.appendChild(trafficPanel(recent, hour));

	const lower = element('<section class="split"></section>');
	lower.appendChild(refusalPanel(data));
	lower.appendChild(pipelinePanel(data));
	page.appendChild(lower);

	// Meant to be left open on a second screen, so it keeps itself current.
	if (healthTimer) clearTimeout(healthTimer);
	healthTimer = setTimeout(() => { if (current === 'health') render(); }, 30000);
	return page;
}

/** ok | warn | bad, so the colour says the same thing the number does. */
function grade(value, warn, bad) {
	if (value >= bad) return 'bad';
	if (value >= warn) return 'warn';
	return 'ok';
}

function duration(seconds) {
	if (seconds === null || seconds === undefined) return '\u2014';
	if (seconds < 90) return Math.round(seconds) + 's';
	if (seconds < 5400) return Math.round(seconds / 60) + 'm';
	if (seconds < 172800) return Math.round(seconds / 3600) + 'h';
	return Math.round(seconds / 86400) + 'd';
}

function healthStrip(data, hour) {
	const refused = hour.refused_share * 100;
	const strip = element('<section class="totals"></section>');
	[
		['Requests / h', hour.total, 'ok'],
		['Refused', refused.toFixed(1) + '%', grade(refused, 1, 5)],
		['Rate limited', hour.rate_limited, grade(hour.rate_limited, 1, 50)],
		['Overloaded', hour.overloaded, grade(hour.overloaded, 1, 20)],
		['Server errors', hour.server_error, grade(hour.server_error, 1, 1)],
		['Uptime', duration(data.uptime_s), 'ok'],
	].forEach(([name, value, state]) => {
		strip.appendChild(element(
			'<div class="t-cell"><span class="t-n ' + state + '">' + escapeHtml(String(value)) +
			'</span><span class="t-l">' + escapeHtml(name) + '</span></div>',
		));
	});
	return strip;
}

function statRow(label, value, state) {
	return '<div class="kv"><span class="k">' + escapeHtml(label) + '</span>' +
		'<span class="v ' + (state || '') + '">' + escapeHtml(String(value)) + '</span></div>';
}

function trafficPanel(recent, hour) {
	const panel = element('<section class="panel"></section>');
	panel.appendChild(element(
		'<div class="panel-head"><h3>Traffic</h3>' +
		'<span class="sub">counters restart with the server</span></div>',
	));
	[['Last 5 minutes', recent], ['Last hour', hour]].forEach(([label, window]) => {
		const share = window.refused_share * 100;
		panel.appendChild(element(
			'<div class="health-block"><h4>' + escapeHtml(label) + '</h4>' +
			statRow('Answered', window.ok) +
			statRow('Rate limited', window.rate_limited, grade(window.rate_limited, 1, 50)) +
			statRow('Overloaded', window.overloaded, grade(window.overloaded, 1, 20)) +
			statRow('Client errors', window.client_error) +
			statRow('Server errors', window.server_error, grade(window.server_error, 1, 1)) +
			statRow('Refused', share.toFixed(1) + '%', grade(share, 1, 5)) +
			'</div>',
		));
	});
	return panel;
}

function refusalPanel(data) {
	const panel = element('<section class="panel"></section>');
	panel.appendChild(element(
		'<div class="panel-head"><h3>Why requests were refused</h3>' +
		'<span class="sub">since restart</span></div>',
	));
	const buckets = Object.entries(data.rate_limited_by_bucket || {}).sort((a, b) => b[1] - a[1]);
	const resources = Object.entries(data.overloaded_by_resource || {}).sort((a, b) => b[1] - a[1]);

	if (!buckets.length && !resources.length) {
		panel.appendChild(empty('Nothing has been turned away.'));
		return panel;
	}
	// Two different things wear the same 429: a quota says "your share, for now", capacity says "try
	// again in a moment". Only the second one is the server's own problem.
	if (buckets.length) {
		panel.appendChild(element(
			'<div class="health-block"><h4>Quota</h4>' +
			buckets.map(([name, count]) => statRow(name, count)).join('') + '</div>',
		));
	}
	if (resources.length) {
		panel.appendChild(element(
			'<div class="health-block"><h4>Capacity</h4>' +
			resources.map(([name, count]) => statRow(name, count, grade(count, 1, 100))).join('') + '</div>',
		));
	}
	return panel;
}

function pipelinePanel(data) {
	const panel = element('<section class="panel"></section>');
	panel.appendChild(element(
		'<div class="panel-head"><h3>Work pipeline</h3><span class="sub">catalogue and merges</span></div>',
	));
	const enrichment = data.enrichment;
	const works = data.works;
	if (!enrichment || !works) {
		panel.appendChild(empty('Not available on this deployment.'));
		return panel;
	}
	panel.appendChild(element(
		'<div class="health-block"><h4>Waiting for a catalogue</h4>' +
		statRow('Queued', enrichment.pending, grade(enrichment.pending, 200, 2000)) +
		statRow('Due now', enrichment.due, grade(enrichment.due, 200, 2000)) +
		// Entries that have already failed are the shape of a catalogue being unreachable.
		statRow('Retrying', enrichment.deferred, grade(enrichment.deferred, 20, 200)) +
		statRow('Oldest', duration(enrichment.oldest_s), grade(enrichment.oldest_s || 0, 3600, 86400)) +
		'</div>',
	));
	panel.appendChild(element(
		'<div class="health-block"><h4>Works</h4>' +
		statRow('Created today', works.created_day) +
		statRow('Merged automatically today', works.auto_merges_day) +
		statRow('Merges undone', works.auto_merges_undone, grade(works.auto_merges_undone, 1, 10)) +
		statRow('Open disputes', works.disputes_open, grade(works.disputes_open, 1, 10)) +
		statRow('Never described by a catalogue', works.backfill_candidates) +
		'</div>',
	));

	// A button rather than a sweep that starts itself: this is thousands of requests to somebody
	// else's free API, which is a decision a person makes.
	if (works.backfill_candidates > 0) {
		const actions = element('<div class="actions"></div>');
		const button = element('<button>Look these up (' + BACKFILL_BATCH + ' at a time)</button>');
		button.onclick = () => run(async () => {
			const note = await askReason(
				'Look up works no catalogue has described',
				'Queues ' + BACKFILL_BATCH + ' of them, most-read first. They are looked up slowly in the ' +
					'background, and any that turn out to be duplicates of a work we already have are merged.',
				false,
			);
			if (note === null) return false;
			await api('/works/backfill', { method: 'POST', body: JSON.stringify({ limit: BACKFILL_BATCH }) });
			// Returning true re-renders the view, so the queue and remaining counts show the result.
			return true;
		}, button);
		actions.appendChild(button);
		panel.appendChild(actions);
	}
	return panel;
}

const BACKFILL_BATCH = 1000;


function sanctionList(users) {
	if (!users.length) return empty('Nobody is under sanction.');
	const table = element(
		'<table><thead><tr><th>User</th><th>Sanction</th><th>Reason</th><th>By</th><th>Applied</th>' +
		'<th>Comments</th><th></th></tr></thead><tbody></tbody></table>',
	);
	const body = table.querySelector('tbody');

	users.forEach((user) => {
		// Both at once is possible and worth seeing as such: a shadowban that was later escalated.
		const marks = [];
		if (user.banned) marks.push('<span class="tag banned">banned</span>');
		if (user.shadowbanned) marks.push('<span class="tag shadowed">shadowbanned</span>');

		const row = element(
			'<tr>' +
				'<td>' + escapeHtml(user.user) + '</td>' +
				'<td>' + marks.join(' ') + '</td>' +
				'<td>' + escapeHtml(user.reason || '') + '</td>' +
				'<td>' + escapeHtml(user.by_moderator || '') + '</td>' +
				'<td>' + (user.sanctioned_at ? when(user.sanctioned_at) : '') + '</td>' +
				'<td>' + user.comments + (user.removed_comments ? ' (' + user.removed_comments + ' removed)' : '') +
				'</td>' +
				'<td class="actions"></td>' +
			'</tr>',
		);
		const actions = row.querySelector('.actions');
		const add = (label, handler) => {
			const button = element('<button>' + label + '</button>');
			button.onclick = () => run(handler, button);
			actions.appendChild(button);
		};

		if (user.banned) {
			add('Unban', async () => {
				const reason = await askReason('Lift the ban', 'They get their account back, comments and all.', false);
				if (reason === null) return false;
				await api('/users/' + user.user_id + '/unban', { method: 'POST', body: JSON.stringify({ reason }) });
				return true;
			});
		}
		if (user.shadowbanned) {
			add('Un-shadowban', async () => {
				const reason = await askReason(
					'Lift the shadowban',
					'Anything they write from now on is visible again. What they wrote while shadowed stays hidden.',
					false,
				);
				if (reason === null) return false;
				await api('/users/' + user.user_id + '/unshadowban', { method: 'POST', body: JSON.stringify({ reason }) });
				return true;
			});
		}
		add('Their comments', async () => {
			const page = await api('/users/' + user.user_id + '/comments');
			show('Comments by ' + user.user, 'Everything they wrote, including what is shadowed or removed.',
				commentList(page.comments, 'Nothing from this account.'));
			return false;
		});
		body.appendChild(row);
	});
	return table;
}
