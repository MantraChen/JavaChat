<template>
  <div class="login-wrap">
    <button type="button" class="theme-toggle-fixed" :title="isDark ? '切换到浅色' : '切换到深色'" @click="auth.toggleTheme()">
      {{ isDark ? '☀️' : '🌙' }}
    </button>
    <div class="login-card">
      <h1>登录</h1>
      <input
        v-model="username"
        type="text"
        placeholder="用户名（如 A、B、C 或已注册账号）"
        autocomplete="username"
        @keydown.enter="focusPass"
      />
      <input
        ref="passRef"
        v-model="password"
        type="password"
        placeholder="Password"
        autocomplete="current-password"
        @keydown.enter="onLogin"
      />
      <button type="button" class="primary" :disabled="loading" @click="onLogin">登录</button>
      <div v-if="auth.loginError" class="error">{{ auth.loginError }}</div>
      <div class="login-links">
        <a href="register.html">注册新账号</a>
        <a href="admin.html">管理员登录</a>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '../stores/auth';
import { useChatStore } from '../stores/chat';

const auth = useAuthStore();
const chat = useChatStore();
const username = ref('');
const password = ref('');
const loading = ref(false);
const passRef = ref(null);

const isDark = computed(() => auth.isDark);

function focusPass() {
  passRef.value?.focus();
}

async function onLogin() {
  const user = username.value?.trim() || '';
  const pass = password.value || '';
  if (!user || !pass) {
    auth.loginError = '请输入用户名和密码';
    return;
  }
  loading.value = true;
  try {
    const ok = await auth.doLogin(user, pass);
    if (ok) {
      chat.connect();
      if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
        Notification.requestPermission();
      }
    }
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  auth.initTheme();
});
</script>
