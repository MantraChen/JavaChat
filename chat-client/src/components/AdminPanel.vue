<template>
  <Teleport to="body">
    <Transition name="modal-fade">
      <div v-if="visible" class="modal-overlay" @click.self="close">
        <div class="modal-container">
          <div class="modal-header">
            <h2><Shield :size="20" /> 管理后台</h2>
            <button class="close-btn" @click="close">
              <X :size="20" />
            </button>
          </div>

          <div class="modal-tabs">
            <button
              class="tab-btn"
              :class="{ active: activeTab === 'pending' }"
              @click="activeTab = 'pending'"  
            >
              待审批 <span class="badge" v-if="pendingUsers.length">{{ pendingUsers.length }}</span>
            </button>
            <button
              class="tab-btn"
              :class="{ active: activeTab === 'all' }"
              @click="activeTab = 'all'"
            >
              所有用户
            </button>
          </div>

          <div class="modal-body">
            <div v-if="loading" class="loading-state">
              <Loader2 :size="24" class="spin" />
              <span>加载中...</span>
            </div>

            <template v-else-if="activeTab === 'pending'">
              <div v-if="pendingUsers.length === 0" class="empty-state">
                <CheckCircle :size="48" />
                <p>暂无待审批用户</p>
              </div>
              <div v-else class="user-list">
                <div v-for="u in pendingUsers" :key="u.userId" class="user-card">
                  <div class="user-avatar">{{ (u.username || 'U').charAt(0).toUpperCase() }}</div>
                  <div class="user-info">
                    <div class="user-name">{{ u.username || '用户' + u.userId }}</div>
                    <div class="user-id">ID: {{ u.userId }}</div>
                  </div>
                  <div class="user-actions">
                    <button class="btn btn-success" @click="approveUser(u.userId)" :disabled="actionLoading">
                      <Check :size="16" /> 通过
                    </button>
                    <button class="btn btn-danger" @click="rejectUser(u.userId)" :disabled="actionLoading">
                      <X :size="16" /> 拒绝
                    </button>
                  </div>
                </div>
              </div>
            </template>

            <template v-else>
              <div v-if="allUsers.length === 0" class="empty-state">
                <Users :size="48" />
                <p>暂无用户</p>
              </div>
              <div v-else class="user-list">
                <div v-for="u in allUsers" :key="u.userId" class="user-card">
                  <div class="user-avatar">{{ (u.username || 'U').charAt(0).toUpperCase() }}</div>
                  <div class="user-info">
                    <div class="user-name">{{ u.username || '用户' + u.userId }}</div>
                    <div class="user-meta">
                      <span class="user-id">ID: {{ u.userId }}</span>
                      <span class="user-status" :class="statusClass(u.status)">{{ statusText(u.status) }}</span>
                    </div>
                  </div>
                  <div class="user-actions">
                    <template v-if="u.status === 'APPROVED'">
                      <button class="btn btn-warning" @click="muteUser(u.userId)" :disabled="actionLoading">
                        <VolumeX :size="16" /> 禁言
                      </button>
                      <button class="btn btn-danger" @click="banUser(u.userId)" :disabled="actionLoading">
                        <Ban :size="16" /> 封禁
                      </button>
                    </template>
                    <template v-else-if="u.status === 'MUTED'">
                      <button class="btn btn-success" @click="unmuteUser(u.userId)" :disabled="actionLoading">
                        <Volume2 :size="16" /> 解除禁言
                      </button>
                    </template>
                    <template v-else-if="u.status === 'BANNED'">
                      <button class="btn btn-success" @click="unbanUser(u.userId)" :disabled="actionLoading">
                        <UserCheck :size="16" /> 解除封禁
                      </button>
                    </template>
                  </div>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch } from 'vue';
import { useAuthStore } from '../stores/auth';
import {
  Shield, X, Loader2, CheckCircle, Users, Check, VolumeX, Volume2, Ban, UserCheck
} from 'lucide-vue-next';

const props = defineProps({
  visible: { type: Boolean, default: false },
});

const emit = defineEmits(['close']);

const auth = useAuthStore();
const activeTab = ref('pending');
const loading = ref(false);
const actionLoading = ref(false);
const pendingUsers = ref([]);
const allUsers = ref([]);

watch(() => props.visible, (v) => {
  if (v) {
    loadPendingUsers();
    loadAllUsers();
  }
});

async function loadPendingUsers() {
  loading.value = true;
  try {
    const res = await fetch('/api/admin/users', {
      headers: { Authorization: 'Bearer ' + auth.token },
    });
    const data = await res.json();
    pendingUsers.value = data.users || [];
  } catch {
    pendingUsers.value = [];
  } finally {
    loading.value = false;
  }
}

async function loadAllUsers() {
  try {
    const res = await fetch('/api/admin/all-users', {
      headers: { Authorization: 'Bearer ' + auth.token },
    });
    const data = await res.json();
    allUsers.value = data.users || [];
  } catch {
    allUsers.value = [];
  }
}

async function adminAction(userId, action) {
  actionLoading.value = true;
  try {
    await fetch('/api/admin/action', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + auth.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ userId, action }),
    });
    await loadAllUsers();
    await loadPendingUsers();
  } finally {
    actionLoading.value = false;
  }
}

async function approveUser(userId) {
  actionLoading.value = true;
  try {
    await fetch('/api/admin/approve', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + auth.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ userId }),
    });
    await loadPendingUsers();
    await loadAllUsers();
  } finally {
    actionLoading.value = false;
  }
}

async function rejectUser(userId) {
  actionLoading.value = true;
  try {
    await fetch('/api/admin/reject', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + auth.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ userId }),
    });
    await loadPendingUsers();
  } finally {
    actionLoading.value = false;
  }
}

function muteUser(userId) {
  adminAction(userId, 'MUTE');
}

function unmuteUser(userId) {
  adminAction(userId, 'UNMUTE');
}

function banUser(userId) {
  adminAction(userId, 'BAN');
}

function unbanUser(userId) {
  adminAction(userId, 'UNBAN');
}

function statusClass(status) {
  return {
    'status-approved': status === 'APPROVED',
    'status-muted': status === 'MUTED',
    'status-banned': status === 'BANNED',
    'status-pending': status === 'PENDING',
  };
}

function statusText(status) {
  const map = {
    APPROVED: '正常',
    MUTED: '已禁言',
    BANNED: '已封禁',
    PENDING: '待审批',
  };
  return map[status] || status;
}

function close() {
  emit('close');
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(4px);
}

.modal-container {
  width: 90%;
  max-width: 640px;
  max-height: 80vh;
  background: var(--bg-card, #fff);
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

.modal-header h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.close-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--text-secondary, #666);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.close-btn:hover {
  background: var(--hover-bg, #f5f5f5);
}

.modal-tabs {
  display: flex;
  gap: 8px;
  padding: 16px 24px;
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

.tab-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-secondary, #666);
  background: transparent;
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.tab-btn:hover {
  background: var(--hover-bg, #f5f5f5);
}

.tab-btn.active {
  background: var(--primary, #4a9eff);
  color: #fff;
  border-color: var(--primary, #4a9eff);
}

.badge {
  background: #e53935;
  color: #fff;
  font-size: 12px;
  padding: 2px 6px;
  border-radius: 10px;
  min-width: 18px;
  text-align: center;
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px;
}

.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 48px;
  color: var(--text-secondary, #888);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.user-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.user-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: var(--bg-secondary, #f7f8fa);
  border-radius: 12px;
}

.user-avatar {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: var(--primary, #4a9eff);
  color: #fff;
  font-weight: 600;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-info {
  flex: 1;
  min-width: 0;
}

.user-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.user-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.user-id {
  font-size: 13px;
  color: var(--text-secondary, #888);
}

.user-status {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
}

.status-approved {
  background: rgba(76, 175, 80, 0.1);
  color: #4caf50;
}

.status-muted {
  background: rgba(255, 152, 0, 0.1);
  color: #ff9800;
}

.status-banned {
  background: rgba(229, 57, 53, 0.1);
  color: #e53935;
}

.status-pending {
  background: rgba(158, 158, 158, 0.1);
  color: #9e9e9e;
}

.user-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 500;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: opacity 0.15s;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-success {
  background: #4caf50;
  color: #fff;
}

.btn-success:hover:not(:disabled) {
  background: #43a047;
}

.btn-danger {
  background: #e53935;
  color: #fff;
}

.btn-danger:hover:not(:disabled) {
  background: #d32f2f;
}

.btn-warning {
  background: #ff9800;
  color: #fff;
}

.btn-warning:hover:not(:disabled) {
  background: #f57c00;
}

/* Transition */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.2s;
}

.modal-fade-enter-active .modal-container,
.modal-fade-leave-active .modal-container {
  transition: transform 0.2s;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}

.modal-fade-enter-from .modal-container,
.modal-fade-leave-to .modal-container {
  transform: scale(0.95);
}

/* Dark mode */
:root[data-theme='dark'] .modal-container {
  background: var(--bg-card, #2d2d2d);
}

:root[data-theme='dark'] .user-card {
  background: var(--bg-secondary, #1a1a1a);
}
</style>
