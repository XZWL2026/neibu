import { computed, ref, watch, type Ref } from 'vue';

/** Client pagination for endpoints that return the complete record collection. */
export function useRecordPagination<T>(records: Readonly<Ref<T[]>>) {
  const page = ref(1);
  const pageSize = 10;
  const pageRecords = computed(() => records.value.slice((page.value - 1) * pageSize, page.value * pageSize));
  watch(records, () => { page.value = 1; });
  return { page, pageRecords, pageSize };
}
