<template>
  <Teleport to="body">
    <TransitionGroup name="toast" tag="div" class="toast-container">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="toast"
        :class="toast.type"
      >
        <CheckCircle v-if="toast.type === 'success'" :size="18" />
        <AlertCircle v-else-if="toast.type === 'error'" :size="18" />
        <Info v-else :size="18" />
        <span>{{ toast.message }}</span>
      </div>
    </TransitionGroup>
  </Teleport>
</template>

<script setup>
import { ref } from 'vue';
import { CheckCircle, AlertCircle, Info } from 'lucide-vue-next';

const toasts = ref([]);
let toastId = 0;

function show(message, type = 'info', duration = 3000) {
  const id = ++toastId;
  toasts.value.push({ id, message, type });
  setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id);
  }, duration);
}

defineExpose({ show });
</script>

<style scoped>
.toast-container {
  position: fixed;
  top: 20px;
  right: 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  z-index: 9999;
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  background: var(--bg-card, #fff);
  border-radius: 10px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary, #333);
  pointer-events: auto;
}

.toast.success {
  background: #e8f5e9;
  color: #2e7d32;
}

.toast.error {
  background: #ffebee;
  color: #c62828;
}

.toast.info {
  background: #e3f2fd;
  color: #1565c0;
}

.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}

.toast-enter-from {
  opacity: 0;
  transform: translateX(100px);
}

.toast-leave-to {
  opacity: 0;
  transform: translateX(100px);
}

:root[data-theme='dark'] .toast {
  background: var(--bg-card, #2d2d2d);
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}

:root[data-theme='dark'] .toast.success {
  background: rgba(46, 125, 50, 0.2);
  color: #81c784;
}

:root[data-theme='dark'] .toast.error {
  background: rgba(198, 40, 40, 0.2);
  color: #ef5350;
}

:root[data-theme='dark'] .toast.info {
  background: rgba(21, 101, 192, 0.2);
  color: #64b5f6;
}
</style>
