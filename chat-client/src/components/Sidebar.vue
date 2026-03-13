<template>
  <aside class="chat-sidebar">
    <div class="sidebar-header">
      <span class="logo">Chat</span>
    </div>

    <div class="sidebar-tabs">
      <span
        class="sidebar-tab"
        :class="{ active: activeTab === 'sessions' }"
        @click="activeTab = 'sessions'"
      >
        <MessageCircle :size="16" />
        <span>会话</span>
      </span>
      <span
        class="sidebar-tab"
        :class="{ active: activeTab === 'contacts' }"
        @click="switchToContacts"
      >
        <Users :size="16" />
        <span>通讯录</span>
      </span>
    </div>

    <div class="sidebar-content">
      <div class="sidebar-panel" v-show="activeTab === 'sessions'">
        <div
          v-for="sid in chat.sessionList"
          :key="sid"
          class="session-item"
          :class="{ active: chat.currentSessionId === sid }"
          @click="selectSession(sid)"
        >
          <div class="item-avatar">{{ sid === chat.PUBLIC ? '🌐' : sid.charAt(0).toUpperCase() }}</div>
          <div class="item-info">
            <div class="item-name">{{ sid === chat.PUBLIC ? '公共大厅' : sid }}</div>
            <div class="item-preview">{{ sid === chat.PUBLIC ? '所有人可见' : '私聊消息' }}</div>
          </div>
        </div>
      </div>

      <div class="sidebar-panel" v-show="activeTab === 'contacts'">
        <div v-if="contactsLoading" class="empty-hint">加载中...</div>
        <div v-else-if="contacts.length === 0" class="empty-hint">暂无其他用户</div>
        <div
          v-for="u in contacts"
          :key="u.username || u.userId"
          class="contact-item"
          @click="selectContact(u.username || ('用户' + u.userId))"
        >
          <div class="item-avatar">{{ (u.username || 'U').charAt(0).toUpperCase() }}</div>
          <div class="item-info">
            <div class="item-name">{{ u.username || '用户' + u.userId }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="sidebar-footer" @click.stop="toggleUserMenu">
      <div class="my-avatar">{{ (auth.userId || '?').charAt(0).toUpperCase() }}</div>
      <div class="my-info">
        <div class="my-name">{{ auth.userId || '用户' }}</div>
        <div class="my-status">
          <span class="status-dot"></span>
          在线
        </div>
      </div>
      <ChevronUp :size="16" class="chevron" :class="{ rotated: showUserMenu }" />

      <Transition name="menu-fade">
        <div class="user-menu" v-if="showUserMenu" @click.stop>
          <div class="menu-item" @click="handleThemeToggle">
            <Sun v-if="auth.isDark" :size="16" />
            <Moon v-else :size="16" />
            <span>{{ auth.isDark ? '切换亮色' : '切换暗色' }}</span>
          </div>
          <div class="menu-item" @click="handleOpenProfile">
            <UserCog :size="16" />
            <span>个人设置</span>
          </div>
          <div class="menu-item" v-if="auth.isAdmin" @click="handleOpenAdmin">
            <Shield :size="16" />
            <span>管理后台</span>
          </div>
          <div class="menu-divider"></div>
          <div class="menu-item danger" @click="handleLogout">
            <LogOut :size="16" />
            <span>退出登录</span>
          </div>
        </div>
      </Transition>
    </div>
  </aside>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue';
import { useAuthStore } from '../stores/auth';
import { useChatStore } from '../stores/chat';
import { getUsers } from '../api';
import { MessageCircle, Users, ChevronUp, Sun, Moon, UserCog, Shield, LogOut } from 'lucide-vue-next';

const auth = useAuthStore();
const chat = useChatStore();

const activeTab = ref('sessions');
const contacts = ref([]);
const contactsLoading = ref(false);
const showUserMenu = ref(false);

const emit = defineEmits(['update:header', 'open-admin', 'open-profile']);

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

function toggleUserMenu() {
  showUserMenu.value = !showUserMenu.value;
}

function closeUserMenu() {
  showUserMenu.value = false;
}

function handleThemeToggle() {
  auth.toggleTheme();
  closeUserMenu();
}

function handleOpenProfile() {
  emit('open-profile');
  closeUserMenu();
}

function handleOpenAdmin() {
  emit('open-admin');
  closeUserMenu();
}

function handleLogout() {
  auth.logout();
  closeUserMenu();
  window.location.reload();
}

onMounted(() => {
  document.addEventListener('click', closeUserMenu);
});

onUnmounted(() => {
  document.removeEventListener('click', closeUserMenu);
});
</script>

<style scoped>
.chat-sidebar {
  display: flex;
  flex-direction: column;
  width: 280px;
  height: 100%;
  background: var(--bg-sidebar, #f7f8fa);
  border-right: 1px solid var(--border-color, #e8e8e8);
}

.sidebar-header {
  padding: 20px 16px 12px;
}

.logo {
  font-size: 20px;
  font-weight: 700;
  color: var(--primary, #4a9eff);
}

.sidebar-tabs {
  display: flex;
  gap: 4px;
  padding: 0 12px;
  margin-bottom: 8px;
}

.sidebar-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  justify-content: center;
  padding: 10px 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary, #666);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.sidebar-tab:hover {
  background: var(--hover-bg, rgba(0, 0, 0, 0.04));
}

.sidebar-tab.active {
  background: var(--primary, #4a9eff);
  color: #fff;
}

.sidebar-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.sidebar-panel {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.session-item,
.contact-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s;
}

.session-item:hover,
.contact-item:hover {
  background: var(--hover-bg, rgba(0, 0, 0, 0.04));
}

.session-item.active {
  background: var(--active-bg, rgba(74, 158, 255, 0.1));
}

.item-avatar {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--primary, #4a9eff);
  color: #fff;
  font-weight: 600;
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.item-info {
  flex: 1;
  min-width: 0;
}

.item-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary, #333);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-preview {
  font-size: 12px;
  color: var(--text-secondary, #888);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.empty-hint {
  padding: 16px;
  text-align: center;
  color: var(--text-muted, #aaa);
  font-size: 13px;
}

/* User Footer */
.sidebar-footer {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-top: 1px solid var(--border-color, #e8e8e8);
  cursor: pointer;
  transition: background 0.15s;
}

.sidebar-footer:hover {
  background: var(--hover-bg, rgba(0, 0, 0, 0.04));
}

.my-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--primary, #4a9eff);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.my-info {
  flex: 1;
  min-width: 0;
}

.my-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.my-status {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-secondary, #888);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #4caf50;
}

.chevron {
  color: var(--text-secondary, #888);
  transition: transform 0.2s;
}

.chevron.rotated {
  transform: rotate(180deg);
}

/* User Menu Popup */
.user-menu {
  position: absolute;
  bottom: 100%;
  left: 12px;
  right: 12px;
  margin-bottom: 8px;
  background: var(--bg-card, #fff);
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.12);
  padding: 6px;
  z-index: 100;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  font-size: 14px;
  color: var(--text-primary, #333);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.menu-item:hover {
  background: var(--hover-bg, #f5f5f5);
}

.menu-item.danger {
  color: #e53935;
}

.menu-item.danger:hover {
  background: rgba(229, 57, 53, 0.08);
}

.menu-divider {
  height: 1px;
  background: var(--border-color, #e8e8e8);
  margin: 6px 0;
}

/* Transition */
.menu-fade-enter-active,
.menu-fade-leave-active {
  transition: opacity 0.15s, transform 0.15s;
}

.menu-fade-enter-from,
.menu-fade-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

/* Dark mode */
:root[data-theme='dark'] .chat-sidebar {
  background: var(--bg-sidebar, #1a1a1a);
}

:root[data-theme='dark'] .user-menu {
  background: var(--bg-card, #2d2d2d);
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}

:root[data-theme='dark'] .menu-item:hover {
  background: var(--hover-bg, #3d3d3d);
}
</style>
