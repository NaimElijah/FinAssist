/**
 * The locale every amount and date on screen is formatted in.
 *
 * Fixed rather than read from `navigator.language`: the same figure then renders as the same
 * string on every machine, in CI and in tests, which is what lets the MoneyText and DateText
 * tests pin exact output. `en-IL` because the interface is written in English and the owner keeps
 * books in shekels, so grouping, currency symbols and day-month-year dates follow Israeli
 * conventions.
 */
export const DISPLAY_LOCALE = 'en-IL';
