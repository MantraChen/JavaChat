import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { login as apiLogin } from '../api';

const THEME_KEY = 'theme';

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '');
  const userId = ref(localStorage.getItem('userId') || '');
  const loginError = ref('');

  watch(token, (v) => {
    if (v) localStorage.setItem('token', v);
    else localStorage.removeItem('token');
  }, { immediate: true });

  watch(userId, (v) => {
    if (v) localStorage.setItem('userId', v);
    else localStorage.removeItem('userId');
  }, { immediate: true });

  const isAuthenticated = () => !!token.value && !!userId.value;

  async function doLogin(username, password) {
    loginError.value = '';
    const result = await apiLogin(username, password);
    if (result.token) {
      token.value = result.token;
      userId.value = username;
      return true;
    }
    loginError.value = result.error || '登录失败';
    return false;
  }

  function logout() {
    token.value = '';
    userId.value = '';
  }

  // 主题
  const isDark = ref(localStorage.getItem(THEME_KEY) === 'dark');
  watch(isDark, (v) => {
    localStorage.setItem(THEME_KEY, v ? 'dark' : 'light');
    if (typeof document !== 'undefined') {
      if (v) document.documentElement.setAttribute('data-theme', 'dark');
      else document.documentElement.removeAttribute('data-theme');
    }
  }, { immediate: true });

  function toggleTheme() {
    isDark.value = !isDark.value;
  }

  function initTheme() {
    if (localStorage.getItem(THEME_KEY) === 'dark') {
      document.documentElement.setAttribute('data-theme', 'dark');
      isDark.value = true;
    } else {
      document.documentElement.removeAttribute('data-theme');
      isDark.value = false;
    }
  }

  return {
    token,
    userId,
    loginError,
    isAuthenticated,
    doLogin,
    logout,
    isDark,
    toggleTheme,
    initTheme,
  };
});
