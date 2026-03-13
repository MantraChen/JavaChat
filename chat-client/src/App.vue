<template>
  <LoginCard v-if="!auth.isAuthenticated()" />
  <div v-else class="chat-screen">
    <header class="chat-header">{{ headerTitle }}</header>
    <div class="chat-layout">
      <Sidebar
        @update:header="headerTitle = $event"
        @open-admin="showAdmin = true"
        @open-profile="showProfile = true"
      />
      <ChatWindow @update-header="headerTitle = $event" />
    </div>
  </div>

  <AdminPanel :visible="showAdmin" @close="showAdmin = false" />
  <ProfileModal :visible="showProfile" @close="showProfile = false" />
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useAuthStore } from './stores/auth';
import { useChatStore } from './stores/chat';
import LoginCard from './components/LoginCard.vue';
import Sidebar from './components/Sidebar.vue';
import ChatWindow from './components/ChatWindow.vue';
import AdminPanel from './components/AdminPanel.vue';
import ProfileModal from './components/ProfileModal.vue';

const auth = useAuthStore();
const chat = useChatStore();
const headerTitle = ref('公共大厅');
const showAdmin = ref(false);
const showProfile = ref(false);

onMounted(() => {
  if (auth.isAuthenticated()) {
    headerTitle.value = '公共大厅';
    if (!chat.ws || chat.ws.readyState !== WebSocket.OPEN) {
      chat.connect();
    }
  }
});
</script>
