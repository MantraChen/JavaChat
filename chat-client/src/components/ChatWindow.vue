<template>
  <main class="chat-main">
    <div ref="messagesRef" class="messages">
      <MessageBubble
        v-for="(item, i) in chat.currentMessages"
        :key="item.data.localId || item.data.messageId || i"
        :data="item.data"
        :is-self="item.isSelf"
        @update-header="$emit('update-header', $event)"
        @show-quote="onShowQuote"
      />
    </div>
    <div v-if="chat.typingText" class="typing-indicator">{{ chat.typingText }}</div>
    <ChatInput @update-header="$emit('update-header', $event)" />
  </main>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue';
import { useChatStore } from '../stores/chat';
import MessageBubble from './MessageBubble.vue';
import ChatInput from './ChatInput.vue';

const chat = useChatStore();
const messagesRef = ref(null);

watch(() => chat.currentMessages.length, () => {
  nextTick(() => {
    if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight;
  });
}, { flush: 'post' });

function onShowQuote() {
  // ChatInput 通过 store.pendingQuote 显示引用预览
}

defineEmits(['update-header']);
</script>
