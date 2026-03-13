import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { getOnlineUsers } from '../api';
import { useAuthStore } from './auth';

const PUBLIC = 'PUBLIC';
const INBOX = 'INBOX';

function genLocalId() {
  return 'l-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
}

function sessionIdForMessage(m, myUserId) {
  const r = m.receiverId;
  if (!r || r === '' || r === PUBLIC) return PUBLIC;
  return m.senderId === myUserId ? r : m.senderId;
}

export const useChatStore = defineStore('chat', () => {
  const auth = useAuthStore();
  const ws = ref(null);
  const authenticated = ref(false);

  const allMessages = ref([]);
  const currentSessionId = ref(PUBLIC);
  const syncCursors = ref({ [PUBLIC]: 0, [INBOX]: 0 });
  const pendingAckTimers = ref({});
  const pendingQuote = ref(null);

  const typingText = ref('');
  const typingClearTimer = ref(null);

  const sessionList = computed(() => {
    const ids = { [PUBLIC]: true };
    allMessages.value.forEach((item) => {
      ids[item.sessionId] = true;
    });
    const order = [PUBLIC];
    Object.keys(ids).forEach((k) => { if (k !== PUBLIC) order.push(k); });
    order.sort((a, b) => {
      if (a === PUBLIC) return -1;
      if (b === PUBLIC) return 1;
      return String(a).localeCompare(b);
    });
    return order;
  });

  const currentMessages = computed(() => {
    return allMessages.value.filter((m) => m.sessionId === currentSessionId.value);
  });

  function getWsUrl() {
    const proto = typeof location !== 'undefined' && location.protocol === 'https:' ? 'wss:' : 'ws:';
    return proto + '//' + (typeof location !== 'undefined' ? location.host : 'localhost:9091') + '/ws';
  }

  function requestSync(target) {
    if (!ws.value || ws.value.readyState !== WebSocket.OPEN) return;
    const payload = { type: 'sync', target, lastTimestamp: syncCursors.value[target] || 0 };
    ws.value.send(JSON.stringify(payload));
  }

  function appendMessage(data, isSelf) {
    const sid = sessionIdForMessage(data, auth.userId);
    allMessages.value.push({ data: { ...data }, isSelf, sessionId: sid });
    if (sid !== PUBLIC) {
      // trigger session list update
    }
  }

  function appendSystemMessage(data) {
    allMessages.value.push({
      data: {
        ...data,
        msgType: 'system',
        senderId: 'SYSTEM',
        messageId: 'sys-' + Date.now(),
      },
      isSelf: false,
      sessionId: PUBLIC,
      isSystem: true,
    });
  }

  function applyAck(localId, messageId) {
    const item = allMessages.value.find((x) => x.data.localId === localId);
    if (item) {
      item.data.messageId = String(messageId);
      item.data.sending = false;
    }
    clearPendingAck(localId);
  }

  function clearPendingAck(localId) {
    if (pendingAckTimers.value[localId]) {
      clearTimeout(pendingAckTimers.value[localId]);
      delete pendingAckTimers.value[localId];
    }
  }

  function markSendFailed(localId) {
    const item = allMessages.value.find((x) => x.data.localId === localId);
    if (item) {
      item.data.sendFailed = true;
      item.data.sending = false;
    }
    clearPendingAck(localId);
  }

  function applyRecall(messageId, senderId) {
    const item = allMessages.value.find((x) => String(x.data.messageId) === String(messageId));
    if (item) item.data.isRecalled = true;
    if (pendingQuote.value && String(pendingQuote.value.id) === String(messageId)) {
      pendingQuote.value = null;
    }
  }

  function showTyping(senderId, status) {
    if (typingClearTimer.value) {
      clearTimeout(typingClearTimer.value);
      typingClearTimer.value = null;
    }
    if (status && senderId && senderId !== auth.userId) {
      typingText.value = senderId + ' 正在输入...';
      typingClearTimer.value = setTimeout(() => {
        typingText.value = '';
        typingClearTimer.value = null;
      }, 3000);
    } else {
      typingText.value = '';
    }
  }

  function sendTypingStatus(status) {
    if (!ws.value || ws.value.readyState !== WebSocket.OPEN || !authenticated.value) return;
    const payload = { type: 'typing', status };
    if (currentSessionId.value && currentSessionId.value !== PUBLIC) {
      payload.receiverId = currentSessionId.value;
    }
    ws.value.send(JSON.stringify(payload));
  }

  function connect() {
    const url = getWsUrl();
    const socket = new WebSocket(url);
    ws.value = socket;

    socket.onopen = () => {
      socket.send(JSON.stringify({ type: 'auth', token: auth.token }));
    };

    socket.onmessage = (ev) => {
      try {
        const data = JSON.parse(ev.data);
        if (data.type === 'auth_ok') {
          authenticated.value = true;
          allMessages.value = [];
          currentSessionId.value = PUBLIC;
          syncCursors.value = { [PUBLIC]: 0, [INBOX]: 0 };
          requestSync(PUBLIC);
          requestSync(INBOX);
        } else if (data.type === 'auth_fail') {
          authenticated.value = false;
        } else if (data.type === 'ack') {
          if (data.localId && data.messageId) applyAck(data.localId, data.messageId);
        } else if (data.type === 'chat') {
          const isSelf = data.senderId === auth.userId;
          if (isSelf) {
            const pending = allMessages.value.find(
              (x) => x.isSelf && x.data.localId && !x.data.messageId && x.sessionId === currentSessionId.value
            );
            if (pending) {
              clearPendingAck(pending.data.localId);
              const idx = allMessages.value.findIndex((x) => x.data.localId === pending.data.localId);
              if (idx >= 0) allMessages.value.splice(idx, 1);
            }
            appendMessage(data, true);
          } else {
            showTyping(data.senderId, false);
            appendMessage(data, false);
          }
          if (data.mentions && data.mentions.indexOf(auth.userId) >= 0 && typeof document !== 'undefined' && document.hidden) {
            try {
              new Notification('新消息 @你', { body: (data.senderId || '') + ': ' + (data.content || '').slice(0, 50) });
            } catch (e) {}
          }
        } else if (data.type === 'typing') {
          showTyping(data.senderId, data.status);
        } else if (data.type === 'RECALL') {
          applyRecall(data.messageId, data.senderId);
        } else if (data.type === 'system') {
          appendSystemMessage(data);
        } else if (data.type === 'sync_result' && Array.isArray(data.messages)) {
          const targetKey = data.target === PUBLIC || data.target === INBOX ? data.target
            : (data.messages.length && data.messages[0].receiverId && data.messages[0].receiverId !== PUBLIC) ? INBOX : PUBLIC;
          if (data.messages.length > 0) {
            const maxTs = data.messages[data.messages.length - 1].timestamp;
            syncCursors.value[targetKey] = Math.max(syncCursors.value[targetKey] || 0, maxTs);
          }
          data.messages.forEach((m) => appendMessage(m, m.senderId === auth.userId));
          if (data.messages.some((m) => m.mentions && m.mentions.indexOf(auth.userId) >= 0) && typeof document !== 'undefined' && document.hidden) {
            try { new Notification('有人 @你'); } catch (e) {}
          }
        } else if (data.type === 'error' && data.reason && data.reason.indexOf('Recall timeout') >= 0) {
          // ignore
        } else if (data.type === 'error') {
          console.warn('Server error:', data.reason);
        }
      } catch (e) {
        console.warn('Parse message error', e);
      }
    };

    socket.onclose = () => {
      authenticated.value = false;
    };
    socket.onerror = () => {
      authenticated.value = false;
    };
  }

  function sendChat(content, msgType = 'text', replyTo = null) {
    if (!content || !ws.value || ws.value.readyState !== WebSocket.OPEN || !authenticated.value) return;
    const mentions = typeof content === 'string' ? (content.match(/@(\w+)/g) || []).map((x) => x.slice(1)) : [];
    const localId = genLocalId();
    const payload = { type: 'chat', content, mentions, localId };
    if (currentSessionId.value && currentSessionId.value !== PUBLIC) payload.receiverId = currentSessionId.value;
    if (msgType) payload.msgType = msgType;
    if (replyTo) {
      payload.replyToId = replyTo.id;
      payload.replyToUser = replyTo.user;
      payload.replyToContent = replyTo.content;
    }
    const optData = {
      localId,
      content,
      senderId: auth.userId,
      timestamp: Date.now(),
      sending: true,
      mentions: payload.mentions,
    };
    if (msgType) optData.msgType = msgType;
    allMessages.value.push({ data: optData, isSelf: true, sessionId: currentSessionId.value });
    pendingAckTimers.value[localId] = setTimeout(() => markSendFailed(localId), 5000);
    ws.value.send(JSON.stringify(payload));
    return localId;
  }

  function sendRecall(messageId) {
    if (!ws.value || ws.value.readyState !== WebSocket.OPEN) return;
    ws.value.send(JSON.stringify({ type: 'RECALL', messageId }));
  }

  function setCurrentSession(sid) {
    currentSessionId.value = sid;
    const target = sid === PUBLIC ? PUBLIC : INBOX;
    requestSync(target);
  }

  async function loadOnlineForMention() {
    const list = await getOnlineUsers(auth.token);
    return (list || []).filter((u) => String(u) !== String(auth.userId));
  }

  return {
    ws,
    authenticated,
    allMessages,
    currentSessionId,
    currentMessages,
    sessionList,
    syncCursors,
    pendingQuote,
    typingText,
    requestSync,
    appendMessage,
    applyAck,
    markSendFailed,
    applyRecall,
    showTyping,
    sendTypingStatus,
    connect,
    sendChat,
    sendRecall,
    setCurrentSession,
    loadOnlineForMention,
    clearPendingAck,
    genLocalId,
    PUBLIC,
    INBOX,
  };
});
