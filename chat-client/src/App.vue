<template>
  <LoginCard v-if="!auth.isAuthenticated()" />
  <div v-else class="chat-screen">
    <header class="chat-header">{{ headerTitle }}</header>
    <div class="chat-layout">
      <Sidebar @update:header="headerTitle = $event" />
      <ChatWindow @update-header="headerTitle = $event" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useAuthStore } from './stores/auth';
import { useChatStore } from './stores/chat';
import LoginCard from './components/LoginCard.vue';
import Sidebar from './components/Sidebar.vue';
import ChatWindow from './components/ChatWindow.vue';

const auth = useAuthStore();
const chat = useChatStore();
const headerTitle = ref('公共大厅');

onMounted(() => {
  if (auth.isAuthenticated()) {
    headerTitle.value = '公共大厅';
    if (!chat.ws || chat.ws.readyState !== WebSocket.OPEN) {
      chat.connect();
    }
  }
});
</script>
