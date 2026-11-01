/**
 * 点按 / 长按 区分（鼠标与触屏通用）：按住 [delay] ms 不动 = 长按（到点立即触发，不等松手），
 * 提前松手 = 点按；移动超过 [tolerance] px（如滚动）或指针被取消 = 都不算。
 */

export const LONG_PRESS_DELAY = 500;
export const LONG_PRESS_TOLERANCE = 8;

export interface LongPressPoint {
  clientX: number;
  clientY: number;
  /** 鼠标只认左键。 */
  button?: number;
}

export interface LongPressCallbacks {
  onClick: () => void;
  onLongPress: () => void;
  /** 按住中（true）/ 结束（false），用来显示「松手即替换」之类的进度。 */
  onPressingChange?: (pressing: boolean) => void;
}

export interface LongPressTimers {
  set: (callback: () => void, ms: number) => unknown;
  clear: (handle: unknown) => void;
}

const defaultTimers: LongPressTimers = {
  set: (callback, ms) => setTimeout(callback, ms),
  clear: handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

export function createLongPress(
  callbacks: () => LongPressCallbacks,
  { delay = LONG_PRESS_DELAY, tolerance = LONG_PRESS_TOLERANCE, timers = defaultTimers }: { delay?: number; tolerance?: number; timers?: LongPressTimers } = {},
) {
  let origin: { x: number; y: number } | null = null;
  let timer: unknown = null;
  const reset = () => {
    if (timer !== null) timers.clear(timer);
    timer = null;
    if (origin) callbacks().onPressingChange?.(false);
    origin = null;
  };
  return {
    down(point: LongPressPoint) {
      if ((point.button ?? 0) !== 0) return;
      reset();
      origin = { x: point.clientX, y: point.clientY };
      callbacks().onPressingChange?.(true);
      timer = timers.set(() => {
        timer = null;
        origin = null;
        const current = callbacks();
        current.onPressingChange?.(false);
        current.onLongPress();
      }, delay);
    },
    move(point: LongPressPoint) {
      if (origin && Math.hypot(point.clientX - origin.x, point.clientY - origin.y) > tolerance) reset();
    },
    up() {
      const click = origin !== null && timer !== null;
      reset();
      if (click) callbacks().onClick();
    },
    cancel: reset,
  };
}
