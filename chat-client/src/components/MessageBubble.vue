<template>
  <div
    class="msg"
    :class="[isSelf ? 'self' : 'other', { mention: isMention, recalled: data.isRecalled, sending: data.sending, 'send-failed': data.sendFailed }]"
    :data-message-id="data.messageId"
    :data-local-id="data.localId"
  >
    <div class="sender" :title="!isSelf && data.senderId ? '点击发起私聊' : ''" @click="onSenderClick">
      {{ data.senderId || '—' }}
    </div>
    <div v-if="data.replyToId && (data.replyToUser || data.replyToContent)" class="quote-bar" @click="scrollToReply">
      {{ (data.replyToUser ? data.replyToUser + ': ' : '') + (data.replyToContent || '').slice(0, 80) }}
    </div>
    <div class="msg-text">
      <template v-if="data.isRecalled">
        {{ (data.senderId || '') + ' 撤回了一条消息' }}
      </template>
      <template v-else-if="data.msgType === 'image' && data.content">
        <img
          :src="data.content"
          alt="图片"
          class="msg-image"
          loading="lazy"
          @click="openImage"
        />
      </template>
      <template v-else>
        {{ data.content || '' }}
      </template>
    </div>
    <div v-if="!data.isRecalled" class="msg-actions">
      <template v-if="data.sendFailed">
        <span class="send-fail-hint">发送失败 </span>
        <a href="javascript:;" @click.prevent="resend">重发</a>
      </template>
      <template v-else>
        <a href="javascript:;" @click.prevent="quote">引用</a>
        <a v-if="canRecall" href="javascript:;" @click.prevent="recall">撤回</a>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useAuthStore } from '../stores/auth';
import { useChatStore } from '../stores/chat';

const props = defineProps({
  data: { type: Object, required: true },
  isSelf: { type: Boolean, default: false },
});

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
  const el = document.querySelector('.msg[data-message-id="' + props.data.replyToId + '"]');
  if (el) el.scrollIntoView({ behavior: 'smooth' });
}

function openImage() {
  if (props.data.content) window.open('', '_blank')?.document.write('<img src="' + props.data.content + '" style="max-width:100%;">');
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

const emit = defineEmits(['update-header', 'show-quote']);
</script>
