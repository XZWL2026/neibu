// JSDOM has dialog elements but lacks the browser modal methods.
Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {configurable: true, value() { this.setAttribute('open', ''); }});
Object.defineProperty(HTMLDialogElement.prototype, 'close', {configurable: true, value() { this.removeAttribute('open'); }});
