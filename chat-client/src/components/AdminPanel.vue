<template>
  <Teleport to="body">
    <Transition name="modal-fade">
      <div v-if="visible" class="admin-overlay" @click.self="close">
        <div class="admin-container">
          <!-- Sidebar -->
          <aside class="admin-sidebar">
            <div class="sidebar-header">
              <Shield :size="24" />
              <span>管理后台</span>
            </div>
            <nav class="sidebar-nav">
              <button
                v-for="item in navItems"
                :key="item.id"
                class="nav-item"
                :class="{ active: activeSection === item.id }"
                @click="activeSection = item.id"
              >
                <component :is="item.icon" :size="18" />
                <span>{{ item.label }}</span>
                <span v-if="item.badge" class="nav-badge">{{ item.badge }}</span>
              </button>
            </nav>
            <div class="sidebar-footer">
              <button class="close-link" @click="close">
                <ArrowLeft :size="16" />
                返回聊天
              </button>
            </div>
          </aside>

          <!-- Main Content -->
          <main class="admin-main">
            <!-- Dashboard -->
            <section v-if="activeSection === 'dashboard'" class="section">
              <h2 class="section-title">数据概览</h2>
              <div class="stats-grid">
                <div class="stat-card">
                  <div class="stat-icon online"><Wifi :size="24" /></div>
                  <div class="stat-info">
                    <div class="stat-value">{{ stats.online }}</div>
                    <div class="stat-label">在线用户</div>
                  </div>
                </div>
                <div class="stat-card">
                  <div class="stat-icon pending"><Clock :size="24" /></div>
                  <div class="stat-info">
                    <div class="stat-value">{{ stats.pending }}</div>
                    <div class="stat-label">待审批</div>
                  </div>
                </div>
                <div class="stat-card">
                  <div class="stat-icon total"><Users :size="24" /></div>
                  <div class="stat-info">
                    <div class="stat-value">{{ stats.total }}</div>
                    <div class="stat-label">总用户数</div>
                  </div>
                </div>
                <div class="stat-card">
                  <div class="stat-icon banned"><Ban :size="24" /></div>
                  <div class="stat-info">
                    <div class="stat-value">{{ stats.banned }}</div>
                    <div class="stat-label">已封禁</div>
                  </div>
                </div>
              </div>

              <!-- Quick Broadcast -->
              <div class="broadcast-section">
                <h3 class="subsection-title">
                  <Megaphone :size="18" />
                  系统广播
                </h3>
                <div class="broadcast-form">
                  <input
                    v-model="broadcastMessage"
                    type="text"
                    class="broadcast-input"
                    placeholder="输入系统公告内容..."
                    @keydown.enter="sendBroadcast"
                  />
                  <button
                    class="btn btn-primary"
                    :disabled="!broadcastMessage.trim() || broadcasting"
                    @click="sendBroadcast"
                  >
                    <Send :size="16" />
                    发送
                  </button>
                </div>
              </div>
            </section>

            <!-- Pending Approvals -->
            <section v-else-if="activeSection === 'pending'" class="section">
              <div class="section-header">
                <h2 class="section-title">注册审批</h2>
                <div class="batch-actions" v-if="pendingUsers.length > 0">
                  <button class="btn btn-outline" @click="approveAll" :disabled="actionLoading">
                    <CheckCheck :size="16" /> 全部通过
                  </button>
                </div>
              </div>

              <div v-if="loading" class="loading-state">
                <Loader2 :size="24" class="spin" />
                <span>加载中...</span>
              </div>
              <div v-else-if="pendingUsers.length === 0" class="empty-state">
                <CheckCircle :size="48" />
                <p>暂无待审批用户</p>
              </div>
              <div v-else class="user-table">
                <div v-for="u in pendingUsers" :key="u.userId" class="table-row">
                  <div class="user-cell">
                    <div class="user-avatar">{{ (u.username || 'U').charAt(0).toUpperCase() }}</div>
                    <div class="user-info">
                      <div class="user-name">{{ u.username || '用户' + u.userId }}</div>
                      <div class="user-id">ID: {{ u.userId }}</div>
                    </div>
                  </div>
                  <div class="action-cell">
                    <button class="btn btn-success btn-sm" @click="approveUser(u.userId)" :disabled="actionLoading">
                      <Check :size="14" /> 通过
                    </button>
                    <button class="btn btn-danger btn-sm" @click="rejectUser(u.userId)" :disabled="actionLoading">
                      <X :size="14" /> 拒绝
                    </button>
                  </div>
                </div>
              </div>
            </section>

            <!-- User Management -->
            <section v-else-if="activeSection === 'users'" class="section">
              <div class="section-header">
                <h2 class="section-title">用户管理</h2>
                <div class="search-box">
                  <Search :size="16" />
                  <input
                    v-model="searchKeyword"
                    type="text"
                    placeholder="搜索用户名..."
                  />
                </div>
              </div>

              <div v-if="loading" class="loading-state">
                <Loader2 :size="24" class="spin" />
              </div>
              <div v-else-if="filteredUsers.length === 0" class="empty-state">
                <Users :size="48" />
                <p>{{ searchKeyword ? '未找到匹配用户' : '暂无用户' }}</p>
              </div>
              <div v-else class="user-table">
                <div class="table-header">
                  <span class="col-user">用户</span>
                  <span class="col-status">状态</span>
                  <span class="col-actions">操作</span>
                </div>
                <div v-for="u in filteredUsers" :key="u.userId" class="table-row">
                  <div class="user-cell">
                    <div class="user-avatar">{{ (u.username || 'U').charAt(0).toUpperCase() }}</div>
                    <div class="user-info">
                      <div class="user-name">{{ u.username || '用户' + u.userId }}</div>
                      <div class="user-id">ID: {{ u.userId }}</div>
                    </div>
                  </div>
                  <div class="status-cell">
                    <span class="status-tag" :class="statusClass(u.status)">
                      {{ statusText(u.status) }}
                    </span>
                  </div>
                  <div class="action-cell">
                    <template v-if="u.status === 'APPROVED'">
                      <div class="action-dropdown">
                        <select class="action-select warning" @change="onMuteSelect($event, u.userId)" :disabled="actionLoading">
                          <option value="">禁言</option>
                          <option value="1">1小时</option>
                          <option value="24">24小时</option>
                          <option value="168">7天</option>
                          <option value="0">永久</option>
                        </select>
                        <VolumeX :size="14" class="select-icon" />
                      </div>
                      <div class="action-dropdown">
                        <select class="action-select danger" @change="onBanSelect($event, u.userId)" :disabled="actionLoading">
                          <option value="">封禁</option>
                          <option value="24">24小时</option>
                          <option value="168">7天</option>
                          <option value="720">30天</option>
                          <option value="0">永久</option>
                        </select>
                        <Ban :size="14" class="select-icon" />
                      </div>
                    </template>
                    <template v-else-if="u.status === 'MUTED'">
                      <button class="btn btn-success btn-sm" @click="unmuteUser(u.userId)" :disabled="actionLoading">
                        <Volume2 :size="14" /> 解除禁言
                      </button>
                    </template>
                    <template v-else-if="u.status === 'BANNED'">
                      <button class="btn btn-success btn-sm" @click="unbanUser(u.userId)" :disabled="actionLoading">
                        <UserCheck :size="14" /> 解除封禁
                      </button>
                    </template>
                  </div>
                </div>
              </div>
            </section>

            <!-- Message Moderation -->
            <section v-else-if="activeSection === 'messages'" class="section">
              <h2 class="section-title">消息管控</h2>
              <div class="recall-form">
                <p class="form-hint">输入消息 ID 可强制撤回任意消息（无视时间限制）</p>
                <div class="recall-row">
                  <input
                    v-model="recallMessageId"
                    type="text"
                    class="form-input"
                    placeholder="消息 ID"
                  />
                  <button
                    class="btn btn-danger"
                    :disabled="!recallMessageId.trim() || recalling"
                    @click="forceRecall"
                  >
                    <Trash2 :size="16" />
                    强制撤回
                  </button>
                </div>
              </div>
            </section>
          </main>
        </div>
      </div>
    </Transition>
  </Teleport>

  <Toast ref="toastRef" />
</template>

<script setup>
import { ref, computed, watch, markRaw } from 'vue';
import { useAuthStore } from '../stores/auth';
import Toast from './Toast.vue';
import {
  Shield, ArrowLeft, Wifi, Clock, Users, Ban, Megaphone, Send,
  CheckCheck, Loader2, CheckCircle, Check, X, Search, Volume2,
  UserCheck, Trash2, LayoutDashboard, UserCog, MessageSquare, VolumeX
} from 'lucide-vue-next';

const props = defineProps({
  visible: { type: Boolean, default: false },
});

const emit = defineEmits(['close']);

const auth = useAuthStore();
const toastRef = ref(null);
const activeSection = ref('dashboard');
const loading = ref(false);
const actionLoading = ref(false);
const pendingUsers = ref([]);
const allUsers = ref([]);
const searchKeyword = ref('');
const broadcastMessage = ref('');
const broadcasting = ref(false);
const recallMessageId = ref('');
const recalling = ref(false);

const stats = computed(() => ({
  online: onlineCount.value,
  pending: pendingUsers.value.length,
  total: allUsers.value.length,
  banned: allUsers.value.filter((u) => u.status === 'BANNED').length,
}));

const onlineCount = ref(0);

const navItems = computed(() => [
  { id: 'dashboard', label: '数据大盘', icon: markRaw(LayoutDashboard) },
  { id: 'pending', label: '注册审批', icon: markRaw(Clock), badge: pendingUsers.value.length || null },
  { id: 'users', label: '用户管理', icon: markRaw(UserCog) },
  { id: 'messages', label: '消息管控', icon: markRaw(MessageSquare) },
]);

const filteredUsers = computed(() => {
  if (!searchKeyword.value.trim()) return allUsers.value;
  const kw = searchKeyword.value.toLowerCase();
  return allUsers.value.filter((u) =>
    (u.username || '').toLowerCase().includes(kw) ||
    String(u.userId).includes(kw)
  );
});

watch(() => props.visible, (v) => {
  if (v) {
    loadAll();
  }
});

async function loadAll() {
  loading.value = true;
  await Promise.all([loadPendingUsers(), loadAllUsers(), loadOnlineCount()]);
  loading.value = false;
}

async function loadPendingUsers() {
  try {
    const res = await fetch('/api/admin/users', {
      headers: { Authorization: 'Bearer ' + auth.token },
    });
    const data = await res.json();
    pendingUsers.value = data.users || [];
  } catch {
    pendingUsers.value = [];
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

async function loadOnlineCount() {
  try {
    const res = await fetch('/api/online', {
      headers: { Authorization: 'Bearer ' + auth.token },
    });
    const data = await res.json();
    onlineCount.value = (data.users || []).length;
  } catch {
    onlineCount.value = 0;
  }
}

async function adminAction(userId, action, durationHours = null) {
  actionLoading.value = true;
  try {
    const payload = { userId, action };
    if (durationHours !== null) payload.durationHours = durationHours;
    const res = await fetch('/api/admin/action', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + auth.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });
    if (res.ok) {
      showToast('操作成功', 'success');
      await loadAllUsers();
    } else {
      showToast('操作失败', 'error');
    }
  } catch {
    showToast('网络错误', 'error');
  } finally {
    actionLoading.value = false;
  }
}

async function approveUser(userId) {
  actionLoading.value = true;
  try {
    const res = await fetch('/api/admin/approve', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + auth.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ userId }),
    });
    if (res.ok) {
      showToast('已通过审批', 'success');
      await Promise.all([loadPendingUsers(), loadAllUsers()]);
    } else {
      showToast('操作失败', 'error');
    }
  } finally {
    actionLoading.value = false;
  }
}

async function rejectUser(userId) {
  actionLoading.value = true;
  try {
    const res = await fetch('/api/admin/reject', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + auth.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ userId }),
    });
    if (res.ok) {
      showToast('已拒绝', 'success');
      await loadPendingUsers();
    } else {
      showToast('操作失败', 'error');
    }
  } finally {
    actionLoading.value = false;
  }
}

async function approveAll() {
  for (const u of pendingUsers.value) {
    await approveUser(u.userId);
  }
}

function muteUser(userId, hours = null) { adminAction(userId, 'MUTE', hours); }
function unmuteUser(userId) { adminAction(userId, 'UNMUTE'); }
function banUser(userId, hours = null) { adminAction(userId, 'BAN', hours); }
function unbanUser(userId) { adminAction(userId, 'UNBAN'); }

function onMuteSelect(e, userId) {
  const val = e.target.value;
  if (!val) return;
  const hours = val === '0' ? null : parseInt(val, 10);
  muteUser(userId, hours);
  e.target.value = '';
}

function onBanSelect(e, userId) {
  const val = e.target.value;
  if (!val) return;
  const hours = val === '0' ? null : parseInt(val, 10);
  banUser(userId, hours);
  e.target.value = '';
}

async function sendBroadcast() {
  if (!broadcastMessage.value.trim()) return;
  broadcasting.value = true;
  try {
    const res = await fetch('/api/admin/broadcast', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + auth.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ message: broadcastMessage.value }),
    });
    if (res.ok) {
      showToast('广播已发送', 'success');
      broadcastMessage.value = '';
    } else {
      showToast('发送失败', 'error');
    }
  } catch {
    showToast('网络错误', 'error');
  } finally {
    broadcasting.value = false;
  }
}

async function forceRecall() {
  if (!recallMessageId.value.trim()) return;
  recalling.value = true;
  try {
    const res = await fetch('/api/admin/message/recall', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + auth.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ messageId: recallMessageId.value.trim() }),
    });
    if (res.ok) {
      showToast('消息已撤回', 'success');
      recallMessageId.value = '';
    } else {
      showToast('撤回失败', 'error');
    }
  } catch {
    showToast('网络错误', 'error');
  } finally {
    recalling.value = false;
  }
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
  const map = { APPROVED: '正常', MUTED: '已禁言', BANNED: '已封禁', PENDING: '待审批' };
  return map[status] || status;
}

function showToast(message, type) {
  toastRef.value?.show(message, type);
}

function close() {
  emit('close');
}
</script>

<style scoped>
.admin-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(4px);
}

.admin-container {
  width: 95%;
  max-width: 1100px;
  height: 85vh;
  background: var(--bg-card, #fff);
  border-radius: 16px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.25);
  display: flex;
  overflow: hidden;
}

/* Sidebar */
.admin-sidebar {
  width: 220px;
  background: var(--bg-sidebar, #f7f8fa);
  border-right: 1px solid var(--border-color, #e8e8e8);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.sidebar-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px 20px;
  font-size: 18px;
  font-weight: 700;
  color: var(--primary, #4a9eff);
}

.sidebar-nav {
  flex: 1;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-secondary, #666);
  background: transparent;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s;
  text-align: left;
}

.nav-item:hover {
  background: var(--hover-bg, rgba(0, 0, 0, 0.04));
  color: var(--text-primary, #333);
}

.nav-item.active {
  background: var(--primary, #4a9eff);
  color: #fff;
}

.nav-badge {
  margin-left: auto;
  background: #e53935;
  color: #fff;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 10px;
  min-width: 18px;
  text-align: center;
}

.nav-item.active .nav-badge {
  background: rgba(255, 255, 255, 0.3);
}

.sidebar-footer {
  padding: 16px;
  border-top: 1px solid var(--border-color, #e8e8e8);
}

.close-link {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 16px;
  font-size: 14px;
  color: var(--text-secondary, #666);
  background: transparent;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.close-link:hover {
  background: var(--hover-bg, rgba(0, 0, 0, 0.04));
}

/* Main Content */
.admin-main {
  flex: 1;
  overflow-y: auto;
  padding: 32px;
}

.section-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary, #333);
  margin: 0 0 24px 0;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.section-header .section-title {
  margin: 0;
}

/* Stats Grid */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 32px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: var(--bg-secondary, #f7f8fa);
  border-radius: 14px;
}

.stat-icon {
  width: 52px;
  height: 52px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.stat-icon.online { background: linear-gradient(135deg, #4caf50, #81c784); }
.stat-icon.pending { background: linear-gradient(135deg, #ff9800, #ffb74d); }
.stat-icon.total { background: linear-gradient(135deg, #2196f3, #64b5f6); }
.stat-icon.banned { background: linear-gradient(135deg, #e53935, #ef5350); }

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary, #333);
}

.stat-label {
  font-size: 13px;
  color: var(--text-secondary, #888);
  margin-top: 2px;
}

/* Broadcast */
.broadcast-section {
  background: var(--bg-secondary, #f7f8fa);
  border-radius: 14px;
  padding: 20px;
}

.subsection-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #333);
  margin: 0 0 16px 0;
}

.broadcast-form {
  display: flex;
  gap: 12px;
}

.broadcast-input {
  flex: 1;
  padding: 12px 16px;
  font-size: 14px;
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: 10px;
  background: var(--bg-card, #fff);
  color: var(--text-primary, #333);
}

.broadcast-input:focus {
  outline: none;
  border-color: var(--primary, #4a9eff);
}

/* Search */
.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: var(--bg-secondary, #f7f8fa);
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: 10px;
  color: var(--text-secondary, #888);
}

.search-box input {
  border: none;
  background: transparent;
  font-size: 14px;
  color: var(--text-primary, #333);
  width: 200px;
}

.search-box input:focus {
  outline: none;
}

/* User Table */
.user-table {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.table-header {
  display: flex;
  padding: 12px 16px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary, #888);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.col-user { flex: 1; }
.col-status { width: 100px; text-align: center; }
.col-actions { width: 240px; text-align: right; }

.table-row {
  display: flex;
  align-items: center;
  padding: 16px;
  background: var(--bg-secondary, #f7f8fa);
  border-radius: 12px;
}

.user-cell {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 14px;
}

.user-avatar {
  width: 44px;
  height: 44px;
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

.user-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.user-id {
  font-size: 12px;
  color: var(--text-secondary, #888);
  margin-top: 2px;
}

.status-cell {
  width: 100px;
  text-align: center;
}

.status-tag {
  display: inline-block;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 6px;
}

.status-approved { background: rgba(76, 175, 80, 0.1); color: #4caf50; }
.status-muted { background: rgba(255, 152, 0, 0.1); color: #ff9800; }
.status-banned { background: rgba(229, 57, 53, 0.1); color: #e53935; }
.status-pending { background: rgba(158, 158, 158, 0.1); color: #9e9e9e; }

.action-cell {
  width: 280px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

/* Action Dropdown */
.action-dropdown {
  position: relative;
  display: flex;
  align-items: center;
}

.action-select {
  appearance: none;
  padding: 8px 32px 8px 12px;
  font-size: 13px;
  font-weight: 500;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  color: #fff;
  transition: opacity 0.15s;
}

.action-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-select.warning {
  background: #ff9800;
}

.action-select.danger {
  background: #e53935;
}

.action-select:hover:not(:disabled) {
  opacity: 0.9;
}

.select-icon {
  position: absolute;
  right: 10px;
  pointer-events: none;
  color: rgba(255, 255, 255, 0.8);
}

/* Toggle Switch */
.toggle-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-secondary, #666);
}

.toggle-input {
  display: none;
}

.toggle-slider {
  width: 36px;
  height: 20px;
  background: #ccc;
  border-radius: 10px;
  position: relative;
  transition: background 0.2s;
}

.toggle-slider::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  background: #fff;
  border-radius: 50%;
  transition: transform 0.2s;
}

.toggle-input:checked + .toggle-slider.warning {
  background: #ff9800;
}

.toggle-input:checked + .toggle-slider.danger {
  background: #e53935;
}

.toggle-input:checked + .toggle-slider::after {
  transform: translateX(16px);
}

/* Message Recall */
.recall-form {
  background: var(--bg-secondary, #f7f8fa);
  border-radius: 14px;
  padding: 24px;
}

.form-hint {
  font-size: 14px;
  color: var(--text-secondary, #888);
  margin: 0 0 16px 0;
}

.recall-row {
  display: flex;
  gap: 12px;
}

.form-input {
  flex: 1;
  max-width: 300px;
  padding: 12px 16px;
  font-size: 14px;
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: 10px;
  background: var(--bg-card, #fff);
  color: var(--text-primary, #333);
}

.form-input:focus {
  outline: none;
  border-color: var(--primary, #4a9eff);
}

/* Buttons */
.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  font-size: 14px;
  font-weight: 500;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-sm {
  padding: 8px 12px;
  font-size: 13px;
}

.btn-primary {
  background: var(--primary, #4a9eff);
  color: #fff;
}

.btn-primary:hover:not(:disabled) {
  background: var(--primary-dark, #357abd);
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

.btn-outline {
  background: transparent;
  color: var(--primary, #4a9eff);
  border: 1px solid var(--primary, #4a9eff);
}

.btn-outline:hover:not(:disabled) {
  background: rgba(74, 158, 255, 0.1);
}

/* States */
.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px;
  color: var(--text-secondary, #888);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Transitions */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.2s;
}

.modal-fade-enter-active .admin-container,
.modal-fade-leave-active .admin-container {
  transition: transform 0.2s;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}

.modal-fade-enter-from .admin-container,
.modal-fade-leave-to .admin-container {
  transform: scale(0.95);
}

/* Dark mode */
:root[data-theme='dark'] .admin-container {
  background: var(--bg-card, #2d2d2d);
}

:root[data-theme='dark'] .admin-sidebar {
  background: var(--bg-sidebar, #1a1a1a);
}

:root[data-theme='dark'] .stat-card,
:root[data-theme='dark'] .table-row,
:root[data-theme='dark'] .broadcast-section,
:root[data-theme='dark'] .recall-form {
  background: var(--bg-secondary, #1a1a1a);
}

:root[data-theme='dark'] .broadcast-input,
:root[data-theme='dark'] .form-input {
  background: var(--bg-card, #2d2d2d);
  border-color: var(--border-color, #404040);
}

:root[data-theme='dark'] .search-box {
  background: var(--bg-secondary, #1a1a1a);
  border-color: var(--border-color, #404040);
}
</style>
