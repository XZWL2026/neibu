import { t, formatMessage, useLanguage } from '../i18n';
export function SuiteModules<T extends string>({ options, selected, onChange, disabled = false, required = [], counts = {}, maxCount = 1, onCountChange, title = '套图结构' }: {
  options: { value: T; label: string; description: string }[];
  selected: T[]; onChange(value: T[]): void; disabled?: boolean; required?: T[];
  counts?: Partial<Record<T, number>>; maxCount?: number; onCountChange?(module: T, count: number): void; title?: string;
}) {
  useLanguage();
  return <fieldset className="suite-modules" disabled={disabled}>
    <legend>{t(title)} <span>{formatMessage("{0} 张", selected.reduce((total, module) => total + (counts[module] || 1), 0))}</span></legend>
    <div className="suite-module-grid">{options.map(option => {
      const checked = selected.includes(option.value), count = counts[option.value] || 1;
      return <div className="suite-module" key={option.value} data-selected={checked}>
        <label className="suite-module-choice"><input type="checkbox" checked={checked} disabled={required.includes(option.value)} onChange={event => onChange(event.target.checked
          ? options.filter(item => item.value === option.value || selected.includes(item.value)).map(item => item.value)
          : selected.filter(value => value !== option.value))} />
          <span className="suite-module-copy"><strong>{t(option.label)}</strong><span>{t(option.description)}</span></span>
        </label>
        {onCountChange ? <div className="suite-module-count">
          <button type="button" aria-label={formatMessage("减少{0}数量", option.label)} disabled={!checked || count <= 1} onClick={() => onCountChange(option.value, count - 1)}>−</button>
          <span>{checked ? count : 0}</span>
          <button type="button" aria-label={formatMessage("增加{0}数量", option.label)} disabled={!checked || count >= maxCount} onClick={() => onCountChange(option.value, count + 1)}>＋</button>
        </div> : <small>{formatMessage("{0} 张", checked ? count : 0)}</small>}
      </div>;
    })}</div>
  </fieldset>;
}
