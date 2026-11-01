<template>
  <n-drawer v-model:show="visible" :width="drawerWidth" placement="right" :mask-closable="false"
            :transition="false">
    <n-drawer-content :title="title" closable @close="close">
      <slot />
      <template v-if="$slots.footer" #footer>
        <div class="modal-footer"><slot name="footer" /></div>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  show: Boolean,
  title: { type: String, default: '' },
  width: { type: [String, Number], default: 640 }
})
const emit = defineEmits(['update:show'])
const visible = computed({ get: () => props.show, set: (value) => emit('update:show', value) })
const drawerWidth = computed(() => typeof props.width === 'number' ? `min(${props.width}px, 92vw)` : props.width)
const close = () => { visible.value = false }
</script>

<style scoped>
.modal-footer { display: flex; align-items: center; justify-content: flex-end; }
</style>
