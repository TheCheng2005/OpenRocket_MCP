// Dialogs opened from anywhere (the search, a shortcut, a page): the search itself, Settings at a section ("" for the
// top), and the Guide at a tab.
export const ui = $state({ search: false, settings: null as string | null, guide: null as string | null });
