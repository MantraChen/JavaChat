<template>
  <aside class="chat-sidebar">
    <div class="title">会话</div>
    <div class="sidebar-tabs">
      <span
        class="sidebar-tab"
        :class="{ active: activeTab === 'sessions' }"
        data-tab="sessions"
        @click="activeTab = 'sessions'"
      >会话</span>
      <span
        class="sidebar-tab"
        :class="{ active: activeTab === 'contacts' }"
        data-tab="contacts"
        @click="switchToContacts"
      >通讯录</span>
    </div>
    <div class="sidebar-panel" :class="{ hidden: activeTab !== 'sessions' }">
      <div
        v-for="sid in chat.sessionList"
        :key="sid"
        class="session-item"
        :class="{ active: chat.currentSessionId === sid }"
        @click="selectSession(sid)"
      >
        {{ sid === chat.PUBLIC ? '公共大厅' : '与 ' + sid + ' 私聊' }}
      </div>
    </div>
    <div class="sidebar-panel" :class="{ hidden: activeTab !== 'contacts' }">
      <div v-if="contactsLoading" style="padding:12px 16px;color:var(--text-muted);">加载中...</div>
      <div v-else-if="contacts.length === 0" style="padding:12px 16px;color:var(--text-muted);">暂无其他用户</div>
      <div
        v-for="u in contacts"
        :key="u.username || u.userId"
        class="contact-item"
        @click="selectContact(u.username || ('用户' + u.userId))"
      >
        {{ u.username || '用户' + u.userId }}
      </div>
    </div>
    <div class="sidebar-footer">
      <button type="button" class="theme-btn" :title="auth.isDark ? '浅色' : '深色'" @click="auth.toggleTheme()">
        {{ auth.isDark ? '☀️' : '🌙' }}
      </button>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import { useAuthStore } from '../stores/auth';
import { useChatStore } from '../stores/chat';
import { getUsers } from '../api';

const auth = useAuthStore();
const chat = useChatStore();

const activeTab = ref('sessions');
const contacts = ref([]);
const contactsLoading = ref(false);

watch(() => activeTab.value, (t) => {
  if (t === 'contacts') loadContacts();
});

function loadContacts() {
  if (!auth.token) return;
  contactsLoading.value = true;
  contacts.value = [];
  getUsers(auth.token)
    .then((users) => {
      contacts.value = (users || []).filter((u) => String(u.username || u.userId) !== String(auth.userId));
    })
    .catch(() => {})
    .finally(() => { contactsLoading.value = false; });
}

function switchToContacts() {
  activeTab.value = 'contacts';
}

function selectSession(sid) {
  chat.setCurrentSession(sid);
  emit('update:header', sid === chat.PUBLIC ? '公共大厅' : '与 ' + sid + ' 聊天中');
}

function selectContact(username) {
  chat.setCurrentSession(username);
  activeTab.value = 'sessions';
  emit('update:header', '与 ' + username + ' 聊天中');
}

const emit = defineEmits(['update:header']);
</script>
