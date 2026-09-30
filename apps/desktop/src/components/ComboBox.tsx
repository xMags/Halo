import { useState } from 'react'
import { FluentIcon } from './FluentIcon'
import { Menu, MenuAnchor, MenuItem } from './Menu'

export interface ComboBoxOption<T extends string | number> {
  value: T
  label: string
}

/**
 * WinUI 3 ComboBox control reproduction: 32px height, subtle control surface
 * with a strong-stroke bottom edge, thin chevron down, and a flyout menu.
 */
export function ComboBox<T extends string | number>({
  options,
  value,
  onChange,
  width = 148,
  minWidth,
  ariaLabel,
}: {
  options: ReadonlyArray<ComboBoxOption<T>>
  value: T
  onChange: (value: T) => void
  width?: number | string
  minWidth?: number | string
  ariaLabel?: string
}) {
  const [open, setOpen] = useState(false)
  const current = options.find((o) => o.value === value) ?? options[0]

  return (
    <MenuAnchor>
      <button
        type="button"
        className="combo-box"
        style={{ width, minWidth }}
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className="combo-box-label ellipsis">{current?.label ?? ''}</span>
        <span className="combo-box-chevron">
          <FluentIcon glyph="chevronDown" size={12} />
        </span>
      </button>
      <Menu
        open={open}
        onClose={() => setOpen(false)}
        minWidth={typeof width === 'number' ? width : 148}
      >
        {options.map((option) => (
          <MenuItem
            key={String(option.value)}
            label={option.label}
            radio
            checked={option.value === value}
            onClick={() => {
              onChange(option.value)
              setOpen(false)
            }}
          />
        ))}
      </Menu>
    </MenuAnchor>
  )
}
