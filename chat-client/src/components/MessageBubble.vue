<template>
  <div
    class="message-row"
    :class="[isSelf ? 'is-self' : 'is-other', { mention: isMention, recalled: data.isRecalled, sending: data.sending, 'send-failed': data.sendFailed }]"
    :data-message-id="data.messageId"
    :data-local-id="data.localId"
  >
    <div class="avatar" :title="!isSelf && data.senderId ? '点击发起私聊' : ''" @click="onSenderClick">
      {{ (data.senderId || '?').charAt(0).toUpperCase() }}
    </div>

    <div class="message-content">
      <div class="sender-name" v-if="!isSelf">{{ data.senderId || '—' }}</div>

      <div v-if="data.replyToId && (data.replyToUser || data.replyToContent)" class="quote-bar" @click="scrollToReply">
        <Reply :size="12" class="quote-icon" />
        {{ (data.replyToUser ? data.replyToUser + ': ' : '') + (data.replyToContent || '').slice(0, 80) }}
      </div>

      <div class="bubble-wrapper">
        <div class="bubble">
          <template v-if="data.isRecalled">
            <span class="recalled-text">{{ (data.senderId || '') + ' 撤回了一条消息' }}</span>
          </template>
          <template v-else-if="data.msgType === 'image' && data.content">
            <img :src="data.content" alt="图片" class="msg-image" loading="lazy" @click="openImage" />
          </template>
          <template v-else>
            {{ data.content || '' }}
          </template>
        </div>

        <div class="action-bar" v-if="!data.isRecalled && !data.sendFailed">
          <button class="action-btn" title="引用" @click="quote">
            <MessageSquareQuote :size="16" />
          </button>
          <button class="action-btn" title="复制" @click="copyText">
            <Copy :size="16" />
          </button>
          <button class="action-btn danger" title="撤回" v-if="canRecall" @click="recall">
            <Undo2 :size="16" />
          </button>
        </div>
      </div>

      <div v-if="data.sendFailed" class="send-fail-row">
        <AlertCircle :size="14" class="fail-icon" />
        <span class="send-fail-hint">发送失败</span>
        <a href="javascript:;" class="resend-link" @click.prevent="resend">重发</a>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useAuthStore } from '../stores/auth';
import { useChatStore } from '../stores/chat';
import { MessageSquareQuote, Copy, Undo2, Reply, AlertCircle } from 'lucide-vue-next';

const props = defineProps({
  data: { type: Object, required: true },
  isSelf: { type: Boolean, default: false },
});

const emit = defineEmits(['update-header', 'show-quote']);

const auth = useAuthStore();
const chat = useChatStore();

const isMention = computed(() => {
  const m = props.data.mentions;
  return m && Array.isArray(m) && m.indexOf(auth.userId) >= 0;
});

const canRecall = computed(() => {
  if (!props.isSelf || !props.data.messageId) return false;
  const open = chat.ws && chat.ws.readyState === WebSocket.OPEN;
  if (!open || !props.data.timestamp) return false;
  return Date.now() - props.data.timestamp < 2 * 60 * 1000;
});

function onSenderClick() {
  if (props.isSelf || !props.data.senderId) return;
  chat.setCurrentSession(props.data.senderId);
  emit('update-header', '与 ' + props.data.senderId + ' 聊天中');
}

function scrollToReply() {
  const el = document.querySelector('.message-row[data-message-id="' + props.data.replyToId + '"]');
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function openImage() {
  if (props.data.content) window.open(props.data.content, '_blank');
}

function quote() {
  if (props.data.isRecalled) return;
  chat.pendingQuote = {
    id: props.data.messageId,
    user: props.data.senderId || '',
    content: (props.data.content || '').slice(0, 80),
  };
  emit('show-quote', (props.data.senderId || '') + ': ' + (props.data.content || '').slice(0, 50));
}

function copyText() {
  if (props.data.content) {
    navigator.clipboard.writeText(props.data.content).catch(() => {});
  }
}

function recall() {
  chat.sendRecall(props.data.messageId);
}

function resend() {
  chat.clearPendingAck(props.data.localId);
  const list = chat.allMessages;
  const idx = list.findIndex((x) => x.data.localId === props.data.localId);
  if (idx >= 0) list.splice(idx, 1);
  chat.sendChat(props.data.content, props.data.msgType || 'text', null);
}
</script>

<style scoped>
.message-row {
  display: flex;
  gap: 12px;
  padding: 8px 16px;
  margin-bottom: 4px;
  border-radius: 8px;
  transition: background 0.15s;
}

.message-row:hover {
  background: var(--hover-bg, rgba(0, 0, 0, 0.02));
}

.message-row.is-self {
  flex-direction: row-reverse;
}

.message-row.mention {
  background: var(--mention-bg, rgba(255, 200, 0, 0.08));
}

.message-row.recalled {
  opacity: 0.6;
}

.message-row.sending {
  opacity: 0.7;
}

.avatar {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--primary, #4a9eff);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: default;
  user-select: none;
}

.message-row.is-other .avatar {
  cursor: pointer;
}

.message-row.is-self .avatar {
  background: var(--primary-dark, #357abd);
}

.message-content {
  display: flex;
  flex-direction: column;
  max-width: 70%;
  min-width: 80px;
}

.message-row.is-self .message-content {
  align-items: flex-end;
}

.sender-name {
  font-size: 12px;
  color: var(--text-secondary, #888);
  margin-bottom: 4px;
  font-weight: 500;
}

.quote-bar {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-secondary, #888);
  background: var(--quote-bg, rgba(0, 0, 0, 0.04));
  padding: 4px 8px;
  border-radius: 6px;
  margin-bottom: 4px;
  cursor: pointer;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quote-bar:hover {
  background: var(--quote-hover-bg, rgba(0, 0, 0, 0.08));
}

.quote-icon {
  flex-shrink: 0;
  opacity: 0.6;
}

.bubble-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
}

.is-self .bubble-wrapper {
  flex-direction: row-reverse;
}

.bubble {
  background: var(--bubble-bg, #f0f2f5);
  padding: 10px 14px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.5;
  word-wrap: break-word;
  white-space: pre-wrap;
}

.message-row.is-self .bubble {
  background: var(--bubble-self-bg, #4a9eff);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.message-row.is-other .bubble {
  border-bottom-left-radius: 4px;
}

.recalled-text {
  font-style: italic;
  color: var(--text-secondary, #888);
}

.message-row.is-self .recalled-text {
  color: rgba(255, 255, 255, 0.8);
}

.msg-image {
  max-width: 240px;
  max-height: 200px;
  border-radius: 8px;
  cursor: pointer;
  display: block;
}

.send-fail-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  font-size: 12px;
}

.fail-icon {
  color: #e53935;
}

.send-fail-hint {
  color: #e53935;
}

.resend-link {
  color: var(--primary, #4a9eff);
  text-decoration: none;
}

.resend-link:hover {
  text-decoration: underline;
}

/* Action Bar - Flexbox aligned */
.action-bar {
  display: flex;
  background: var(--bg-card, #ffffff);
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  opacity: 0;
  visibility: hidden;
  transition: opacity 0.2s ease, visibility 0.2s ease;
}

.message-row:hover .action-bar {
  opacity: 1;
  visibility: visible;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: transparent;
  border: none;
  color: var(--text-secondary, #666);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.action-btn:hover {
  background: var(--hover-bg, #f5f5f5);
  color: var(--text-primary, #333);
}

.action-btn.danger:hover {
  background: #fff0f0;
  color: #e53935;
}

.action-btn:first-child {
  border-radius: 7px 0 0 7px;
}

.action-btn:last-child {
  border-radius: 0 7px 7px 0;
}

.action-btn:only-child {
  border-radius: 7px;
}

/* Dark mode */
:root[data-theme='dark'] .bubble {
  background: var(--bubble-bg, #2d2d2d);
}

:root[data-theme='dark'] .action-bar {
  background: var(--bg-card, #2d2d2d);
  border-color: var(--border-color, #404040);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.3);
}

:root[data-theme='dark'] .action-btn:hover {
  background: var(--hover-bg, #3d3d3d);
}

:root[data-theme='dark'] .action-btn.danger:hover {
  background: rgba(229, 57, 53, 0.15);
}
</style>
