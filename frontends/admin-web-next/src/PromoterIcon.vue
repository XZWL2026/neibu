<script setup lang="ts">
import { computed } from 'vue';
import {
  LayoutDashboard, Users, UserRound, UserRoundPlus, Globe, Database, FileText, CreditCard,
  CircleDollarSign, Settings, Landmark, Eye, Copy, Trash2, Download, Upload, Send,
  Headphones, ShoppingCart, Gift, KeyRound, LockKeyhole, RefreshCw, Search, CalendarDays, EyeOff, Cloud, MessageCircle,
  ChartNoAxesColumn, ChevronLeft, ChevronRight, Zap, CheckCheck, Wallet, Network, ShieldCheck,
  X, Check, Clock3, Video, Image, Diamond, Plus, Minus, ChevronDown, ChevronUp,
  ArrowUpRight, ArrowDownLeft, Activity, UserCheck, Crown, Ellipsis, ArrowRight, Layers, ChartNoAxesCombined,
} from '@lucide/vue';
const props = defineProps<{ name: string }>();
const icons = { home: LayoutDashboard, users: Users, user: UserRound, 'user-plus': UserRoundPlus,
  globe: Globe, database: Database, document: FileText, card: CreditCard, money: CircleDollarSign,
  settings: Settings, bank: Landmark, eye: Eye, 'eye-off': EyeOff, cloud: Cloud, message: MessageCircle, copy: Copy, trash: Trash2, download: Download,
  upload: Upload, send: Send, headset: Headphones, cart: ShoppingCart, gift: Gift, lock: LockKeyhole,
  key: KeyRound, refresh: RefreshCw, search: Search, calendar: CalendarDays, chart: ChartNoAxesColumn,
  'chevron-left': ChevronLeft, 'chevron-right': ChevronRight, bolt: Zap, check: CheckCheck,
  wallet: Wallet, network: Network, shield: ShieldCheck,
  close: X, 'check-single': Check, clock: Clock3, video: Video, image: Image, diamond: Diamond,
  plus: Plus, minus: Minus, 'chevron-down': ChevronDown, 'chevron-up': ChevronUp,
  'arrow-up': ArrowUpRight, 'arrow-down': ArrowDownLeft, activity: Activity, 'user-check': UserCheck,
  crown: Crown, more: Ellipsis, 'arrow-right': ArrowRight, layers: Layers, trend: ChartNoAxesCombined,
};
const icon = computed(() => icons[props.name as keyof typeof icons] ?? FileText);
</script>
<template><component :is="icon" class="promoter-icon" :size="18" :stroke-width="1.75" aria-hidden="true" /></template>
