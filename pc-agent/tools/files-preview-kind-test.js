import assert from 'node:assert/strict';
import test from 'node:test';

import { isProbablyText, previewKind } from '../src/files.js';

test('HTML is classified for preview while remaining readable as source text', () => {
  assert.equal(previewKind('page.html', false), 'html');
  assert.equal(previewKind('page.HTM', false), 'html');
  assert.equal(isProbablyText('page.html'), true);
  assert.equal(previewKind('notes.txt', false), 'text');
  assert.equal(previewKind('report.docx', false), 'docx');
});
