import { decimal4, units4 } from './portalAmounts';
export function dayKey(date: Date) { return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`; }
export function monthDates() { const end=new Date(), start=new Date(end.getFullYear(),end.getMonth(),1); return {from:dayKey(start),to:dayKey(end)}; }
export function dateRange(dates: {from:string;to:string}) {
 if(!dates.from||!dates.to||dates.from>dates.to)return null;
 const from=new Date(`${dates.from}T00:00:00`),to=new Date(`${dates.to}T00:00:00`);to.setDate(to.getDate()+1);
 return Number.isFinite(from.getTime())&&Number.isFinite(to.getTime())?{from:from.toISOString(),to:to.toISOString()}:null;
}
export function localTime(value: string|null|undefined) { const d=new Date(value||'');return Number.isFinite(d.getTime())?`${dayKey(d)} ${d.toLocaleTimeString('zh-CN',{hour12:false})}`:'—'; }
export function sumPoints(values: (string|null|undefined)[]) { const parts=values.map(units4);return parts.every(n=>n!==null)?decimal4(parts.reduce<bigint>((sum,n)=>sum+n!,0n)):null; }
export function csv(rows: string[][]) {return '\uFEFF'+rows.map(row=>row.map(value=>'"'+(/^[=+\-@\t\r]/.test(value)?"'"+value:value).replaceAll('"','""')+'"').join(',')).join('\r\n');}
