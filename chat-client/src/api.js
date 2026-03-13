const API_BASE = '';

function url(path) {
  const p = (API_BASE || '') + path;
  return (p.startsWith('http') || p.startsWith('/')) ? p : '/' + p.replace(/^\/*/, '');
}

export async function login(username, password) {
  const res = await fetch(url('/api/login'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const json = await res.json().catch(() => null);
  if (json && json.token) return { token: json.token };
  return { error: (json && json.error) ? json.error : '登录失败' };
}

export async function getUsers(token) {
  const res = await fetch(url('/api/users'), {
    headers: { Authorization: 'Bearer ' + token },
  });
  const json = await res.json().catch(() => null);
  return (json && json.users) ? json.users : [];
}

export async function getOnlineUsers(token) {
  const res = await fetch(url('/api/online'), {
    headers: { Authorization: 'Bearer ' + token },
  });
  const json = await res.json().catch(() => null);
  return (json && json.users) ? json.users : [];
}

export function wsUrl() {
  const base = typeof location !== 'undefined' ? location : { host: 'localhost:9091', protocol: 'http:' };
  const proto = base.protocol === 'https:' ? 'wss:' : 'ws:';
  return proto + '//' + base.host + '/ws';
}
