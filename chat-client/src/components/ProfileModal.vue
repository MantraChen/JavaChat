<template>
  <Teleport to="body">
    <Transition name="modal-fade">
      <div v-if="visible" class="modal-overlay" @click.self="close">
        <div class="modal-container">
          <div class="modal-header">
            <h2><UserCog :size="20" /> 个人设置</h2>
            <button class="close-btn" @click="close">
              <X :size="20" />
            </button>
          </div>

          <div class="modal-body">
            <div class="profile-section">
              <label class="section-label">头像</label>
              <div class="avatar-upload">
                <div class="avatar-preview" @click="triggerFileInput">
                  <img v-if="avatarUrl" :src="avatarUrl" alt="avatar" />
                  <span v-else class="avatar-letter">{{ (nickname || auth.userId || '?').charAt(0).toUpperCase() }}</span>
                  <div class="avatar-overlay">
                    <Camera :size="20" />
                  </div>
                </div>
                <input
                  ref="fileInputRef"
                  type="file"
                  accept="image/*"
                  style="display: none"
                  @change="handleFileSelect"
                />
                <div class="avatar-hint">点击上传新头像</div>
              </div>
            </div>

            <div class="profile-section">
              <label class="section-label" for="nickname-input">昵称</label>
              <input
                id="nickname-input"
                v-model="nickname"
                type="text"
                class="form-input"
                placeholder="设置你的昵称"
                maxlength="20"
              />
              <div class="input-hint">昵称将在聊天中显示</div>
            </div>

            <div class="profile-section readonly">
              <label class="section-label">用户名</label>
              <div class="readonly-value">{{ auth.userId }}</div>
              <div class="input-hint">用户名不可更改</div>
            </div>
          </div>

          <div class="modal-footer">
            <button class="btn btn-secondary" @click="close">取消</button>
            <button class="btn btn-primary" @click="saveProfile" :disabled="saving">
              <Loader2 v-if="saving" :size="16" class="spin" />
              <Save v-else :size="16" />
              保存
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch } from 'vue';
import { useAuthStore } from '../stores/auth';
import { UserCog, X, Camera, Save, Loader2 } from 'lucide-vue-next';

const props = defineProps({
  visible: { type: Boolean, default: false },
});

const emit = defineEmits(['close', 'saved']);

const auth = useAuthStore();
const fileInputRef = ref(null);
const nickname = ref('');
const avatarUrl = ref('');
const saving = ref(false);

watch(() => props.visible, (v) => {
  if (v) {
    loadProfile();
  }
});

async function loadProfile() {
  try {
    const res = await fetch('/api/user/profile', {
      headers: { Authorization: 'Bearer ' + auth.token },
    });
    if (res.ok) {
      const data = await res.json();
      nickname.value = data.nickname || '';
      avatarUrl.value = data.avatarUrl || '';
    }
  } catch {
    nickname.value = '';
    avatarUrl.value = '';
  }
}

function triggerFileInput() {
  fileInputRef.value?.click();
}

async function handleFileSelect(e) {
  const file = e.target.files?.[0];
  if (!file) return;

  const formData = new FormData();
  formData.append('file', file);

  try {
    const res = await fetch('/api/upload', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + auth.token },
      body: formData,
    });
    const data = await res.json();
    if (data.url) {
      avatarUrl.value = data.url;
    }
  } catch (err) {
    console.error('Upload failed:', err);
  }

  e.target.value = '';
}

async function saveProfile() {
  saving.value = true;
  try {
    const res = await fetch('/api/user/profile', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + auth.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        nickname: nickname.value,
        avatarUrl: avatarUrl.value,
      }),
    });
    if (res.ok) {
      emit('saved', { nickname: nickname.value, avatarUrl: avatarUrl.value });
      close();
    }
  } finally {
    saving.value = false;
  }
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
  max-width: 420px;
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

.modal-body {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.profile-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.avatar-upload {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.avatar-preview {
  position: relative;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  background: var(--primary, #4a9eff);
  cursor: pointer;
  overflow: hidden;
}

.avatar-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-letter {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  font-size: 36px;
  font-weight: 600;
  color: #fff;
}

.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  opacity: 0;
  transition: opacity 0.2s;
}

.avatar-preview:hover .avatar-overlay {
  opacity: 1;
}

.avatar-hint {
  font-size: 13px;
  color: var(--text-secondary, #888);
}

.form-input {
  width: 100%;
  padding: 12px 16px;
  font-size: 14px;
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: 10px;
  background: var(--bg-input, #f7f8fa);
  color: var(--text-primary, #333);
  transition: border-color 0.15s, box-shadow 0.15s;
}

.form-input:focus {
  outline: none;
  border-color: var(--primary, #4a9eff);
  box-shadow: 0 0 0 3px rgba(74, 158, 255, 0.1);
}

.input-hint {
  font-size: 12px;
  color: var(--text-secondary, #888);
}

.readonly-value {
  padding: 12px 16px;
  font-size: 14px;
  background: var(--bg-secondary, #f0f2f5);
  border-radius: 10px;
  color: var(--text-secondary, #666);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid var(--border-color, #e8e8e8);
}

.btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 500;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: opacity 0.15s, background 0.15s;
}

.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-secondary {
  background: var(--bg-secondary, #f0f2f5);
  color: var(--text-primary, #333);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--hover-bg, #e8e8e8);
}

.btn-primary {
  background: var(--primary, #4a9eff);
  color: #fff;
}

.btn-primary:hover:not(:disabled) {
  background: var(--primary-dark, #357abd);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
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

:root[data-theme='dark'] .form-input {
  background: var(--bg-input, #1a1a1a);
  border-color: var(--border-color, #404040);
}

:root[data-theme='dark'] .readonly-value {
  background: var(--bg-secondary, #1a1a1a);
}
</style>
