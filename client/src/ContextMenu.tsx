import { useEffect, useRef } from 'react';

export interface MenuItem {
  label: string;
  /** Shown greyed out, with this reason as tooltip; null if the item can be chosen. */
  disabledReason?: string | null;
  /** Shortcut shown on the right, e.g. "Del". */
  shortcut?: string;
  onSelect: () => void;
}

/** A menu at a point on the screen; closes on Escape, a click elsewhere, or a choice. */
export function ContextMenu({
  screen,
  items,
  onClose,
}: {
  screen: { x: number; y: number };
  items: MenuItem[];
  onClose: () => void;
}) {
  const ref = useRef<HTMLUListElement>(null);

  useEffect(() => {
    ref.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
    const onPointerDown = (e: PointerEvent) => {
      if (!ref.current?.contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('pointerdown', onPointerDown, true);
    window.addEventListener('keydown', onKey);
    window.addEventListener('blur', onClose);
    return () => {
      window.removeEventListener('pointerdown', onPointerDown, true);
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('blur', onClose);
    };
  }, [onClose]);

  // Keep the menu inside the window.
  const left = Math.min(screen.x, window.innerWidth - 220);
  const top = Math.min(screen.y, window.innerHeight - (items.length * 30 + 16));

  return (
    <ul
      ref={ref}
      className="context-menu"
      role="menu"
      data-testid="context-menu"
      style={{ left, top }}
      onContextMenu={(e) => e.preventDefault()}
      onKeyDown={(e) => {
        // Arrow keys move between the items.
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
        e.preventDefault();
        const buttons = [...(ref.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])];
        const i = buttons.indexOf(document.activeElement as HTMLButtonElement);
        const next = e.key === 'ArrowDown' ? i + 1 : i - 1;
        buttons[(next + buttons.length) % buttons.length]?.focus();
      }}
    >
      {items.map((item) => (
        <li key={item.label} role="none">
          <button
            type="button"
            role="menuitem"
            disabled={!!item.disabledReason}
            title={item.disabledReason ?? undefined}
            onClick={() => {
              onClose();
              item.onSelect();
            }}
          >
            <span>{item.label}</span>
            {item.shortcut && <span className="context-menu__shortcut">{item.shortcut}</span>}
          </button>
        </li>
      ))}
    </ul>
  );
}
