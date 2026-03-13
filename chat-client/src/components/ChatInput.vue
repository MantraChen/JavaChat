<template>
  <div class="chat-input-wrap">
    <div v-if="chat.pendingQuote" class="quote-preview">
      <span class="quote-user">{{ chat.pendingQuote.user }}: </span>
      <img
        v-if="isQuoteImage"
        :src="chat.pendingQuote.content"
        class="quote-thumb"
        alt="图片"
      />
      <span v-else class="quote-text">{{ quoteDisplay }}</span>
      <span class="clear" @click="clearQuote">×</span>
    </div>
    <div v-show="mentionVisible" class="mention-dropdown">
      <div
        v-for="(u, i) in mentionCandidates"
        :key="u"
        class="item"
        :class="{ selected: i === mentionSelectedIndex }"
        @click="insertMention(u)"
      >
        {{ u }}
      </div>
    </div>
    <div class="chat-input-row">
      <input type="file" ref="fileInputRef" accept="image/*" style="display:none" @change="onFileChange" />
      <button type="button" class="btn-image" title="发送图片" @click="openFileDialog">图片</button>
      <input
        ref="inputRef"
        v-model="inputText"
        type="text"
        placeholder="输入消息，@ 提及他人，回车发送"
        autocomplete="off"
        @input="onInput"
        @blur="onBlur"
        @keydown="onKeydown"
      />
      <button type="button" @click="sendText">发送</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import { useAuthStore } from '../stores/auth';
import { useChatStore } from '../stores/chat';

const auth = useAuthStore();
const chat = useChatStore();

const inputRef = ref(null);
const fileInputRef = ref(null);
const inputText = ref('');

const quoteDisplay = computed(() => {
  const q = chat.pendingQuote;
  if (!q) return '';
  return (q.content || '').slice(0, 50);
});

const isQuoteImage = computed(() => {
  const c = chat.pendingQuote?.content;
  return c && (c.startsWith('data:image/') || c.startsWith('/files/'));
});

const mentionVisible = ref(false);
const mentionCandidates = ref([]);
const mentionSelectedIndex = ref(0);
let mentionStart = -1;

watch(quoteDisplay, (v) => {
  if (!v) return;
});

function clearQuote() {
  chat.pendingQuote = null;
}

let typingTimer = null;
let typingOffTimer = null;

function onInput() {
  const pos = inputRef.value?.selectionStart ?? 0;
  const val = inputText.value;
  const i = val.lastIndexOf('@', pos - 1);
  if (i >= 0 && (i === 0 || /[\s]/.test(val[i - 1]))) {
    const filter = val.slice(i + 1, pos).replace(/\s/g, '');
    openMentionDropdown(filter, i);
  } else {
    mentionVisible.value = false;
    mentionStart = -1;
  }
  if (typingTimer) clearTimeout(typingTimer);
  if (typingOffTimer) clearTimeout(typingOffTimer);
  typingTimer = setTimeout(() => { chat.sendTypingStatus(true); typingTimer = null; }, 400);
  typingOffTimer = setTimeout(() => { chat.sendTypingStatus(false); typingOffTimer = null; }, 2500);
}

function onBlur() {
  chat.sendTypingStatus(false);
  setTimeout(() => { mentionVisible.value = false; mentionStart = -1; }, 150);
}

async function openMentionDropdown(filter, atIndex) {
  mentionStart = atIndex;
  const list = await chat.loadOnlineForMention();
  mentionCandidates.value = list.filter((u) => !filter || String(u).toLowerCase().indexOf(filter.toLowerCase()) >= 0);
  mentionSelectedIndex.value = 0;
  mentionVisible.value = true;
}

function insertMention(username) {
  if (mentionStart < 0) return;
  const val = inputText.value;
  const end = inputRef.value?.selectionEnd ?? val.length;
  inputText.value = val.slice(0, mentionStart) + '@' + username + ' ' + val.slice(end);
  inputRef.value?.focus();
  inputRef.value.selectionStart = inputRef.value.selectionEnd = mentionStart + username.length + 2;
  mentionVisible.value = false;
  mentionStart = -1;
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.isComposing) {
    if (mentionVisible.value && mentionCandidates.value.length > 0) {
      e.preventDefault();
      insertMention(mentionCandidates.value[mentionSelectedIndex.value]);
      return;
    }
    e.preventDefault();
    sendText();
    return;
  }
  if (!mentionVisible.value) return;
  if (e.key === 'Escape') {
    e.preventDefault();
    mentionVisible.value = false;
    mentionStart = -1;
    return;
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    mentionSelectedIndex.value = (mentionSelectedIndex.value + 1) % Math.max(1, mentionCandidates.value.length);
    return;
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault();
    mentionSelectedIndex.value = mentionSelectedIndex.value <= 0 ? Math.max(0, mentionCandidates.value.length - 1) : mentionSelectedIndex.value - 1;
    return;
  }
}

function sendText() {
  const content = inputText.value?.trim();
  if (!content) return;
  const replyTo = chat.pendingQuote || null;
  chat.sendChat(content, 'text', replyTo);
  inputText.value = '';
  if (replyTo) chat.pendingQuote = null;
  inputRef.value?.focus();
}

function onSendImage(content) {
  chat.sendChat(content, 'image', null);
}

function openFileDialog() {
  fileInputRef.value?.click();
}

function onFileChange(e) {
  const file = e.target.files?.[0];
  if (!file || !file.type.startsWith('image/')) return;
  if (file.size > 2 * 1024 * 1024) {
    alert('图片请小于 2MB，以便稳定发送');
    e.target.value = '';
    return;
  }
  const reader = new FileReader();
  reader.onload = (ev) => {
    const base64 = ev.target?.result;
    if (base64) onSendImage(base64);
  };
  reader.onerror = () => alert('读取图片失败');
  reader.readAsDataURL(file);
  e.target.value = '';
}

defineExpose({ focus: () => inputRef.value?.focus() });
</script>

<style scoped>
.quote-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--quote-bg, rgba(0, 0, 0, 0.04));
  border-radius: 8px 8px 0 0;
  font-size: 13px;
  color: var(--text-secondary, #666);
}

.quote-user {
  font-weight: 500;
  color: var(--text-primary, #333);
  flex-shrink: 0;
}

.quote-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quote-thumb {
  max-height: 36px;
  max-width: 60px;
  border-radius: 4px;
  object-fit: cover;
}

.clear {
  margin-left: auto;
  cursor: pointer;
  font-size: 16px;
  color: var(--text-secondary, #888);
  padding: 0 4px;
}

.clear:hover {
  color: var(--text-primary, #333);
}

:root[data-theme='dark'] .quote-preview {
  background: var(--quote-bg, rgba(255, 255, 255, 0.08));
}

:root[data-theme='dark'] .quote-user {
  color: var(--text-primary, #e0e0e0);
}
</style>
