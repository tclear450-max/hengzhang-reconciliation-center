<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { X } from '@lucide/vue'
defineProps<{title:string;wide?:boolean}>()
const emit=defineEmits<{close:[]}>()
const dialog=ref<HTMLDialogElement>()
const key=(event:KeyboardEvent)=>{if(event.key==='Escape')emit('close')}
onMounted(()=>{dialog.value?.showModal();document.addEventListener('keydown',key)})
onBeforeUnmount(()=>document.removeEventListener('keydown',key))
</script>
<template>
  <dialog ref="dialog" class="modal" :class="{wide}" @cancel.prevent="emit('close')" @click="event=>{if(event.target===dialog)emit('close')}">
    <div class="modal-inner"><header class="modal-header"><div><span class="eyebrow">HENGZHANG / WORKSPACE</span><h2>{{title}}</h2></div><button class="icon-button" aria-label="关闭弹窗" @click="emit('close')"><X :size="20"/></button></header><slot/></div>
  </dialog>
</template>
