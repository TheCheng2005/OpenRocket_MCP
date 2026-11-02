// RocketMaxxing keeps one window. The engine cannot bring a browser window to the front, so the Dock, a second launch
// and a double-clicked design open a new one; it tells the older windows, which close (or say to use the new one).

const CHANNEL = "rocketmaxxing.windows";

export function oneWindow(onReplaced: () => void): void {
  if (typeof BroadcastChannel === "undefined") return;
  const me = `${Date.now()}-${Math.random()}`;
  const ch = new BroadcastChannel(CHANNEL);
  ch.onmessage = (e) => {
    if (e.data?.hello && e.data.hello !== me) {
      window.close(); // allowed for a window the app opened; a browser tab may refuse
      setTimeout(onReplaced, 300);
    }
  };
  ch.postMessage({ hello: me });
}

/** The page shown last in this run (same address, so another window of the run picks up where this one was). */
const PAGE = "rocketmaxxing.page";

export function rememberPage(page: string): void {
  try {
    localStorage.setItem(PAGE, page);
  } catch {
    /* private mode */
  }
}

export function lastPage(): string | null {
  try {
    return localStorage.getItem(PAGE);
  } catch {
    return null;
  }
}
