export const LICENSES = {
  'cc-by': 'CC BY 4.0',
  cc0: 'CC0',
  'cc-by-sa': 'CC BY-SA 4.0',
}

/** Empty means the default, CC BY. Unknown codes return null. */
export function normalizeLicense(code) {
  if (code == null || code === '') return 'cc-by'
  return Object.prototype.hasOwnProperty.call(LICENSES, code) ? code : null
}
