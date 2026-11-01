<template>
  <n-button size="small" v-bind="$attrs" @click="$emit('click', $event)">
    <template v-if="icon" #icon>
      <n-icon><component :is="icon" /></n-icon>
    </template>
    <slot>{{ text }}</slot>
  </n-button>
</template>

<script setup>
defineOptions({ inheritAttrs: false })
defineProps({
  icon: { type: Object, default: null },
  text: { type: String, default: '' }
})
defineEmits(['click'])
</script>
