import { fireEvent } from '@testing-library/react';
import { readMentionText, placeMentionCaret } from './RichMaterialInput';
export const readPromptInput = (input: HTMLElement) => input instanceof HTMLTextAreaElement ? input.value : readMentionText(input);
export function editPromptInput(input: HTMLElement, value: string, caret = value.length) {
  if (input instanceof HTMLTextAreaElement) fireEvent.change(input, { target: { value, selectionStart: caret, selectionEnd: caret } });
  else { input.textContent = value; placeMentionCaret(input, caret); fireEvent.input(input); }
}
