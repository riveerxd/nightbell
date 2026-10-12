import { useId } from "react"

/* The bell with the heartbeat knocked out of it, from public/favicon.svg. */
export function Mark({
  size = 24,
  className,
  color = "#2F6BFF",
}: {
  size?: number
  className?: string
  color?: string
}) {
  const id = useId().replace(/:/g, "")
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 512 512"
      aria-hidden="true"
      focusable="false"
      className={className}
    >
      <defs>
        <mask id={id} maskUnits="userSpaceOnUse" x="0" y="0" width="512" height="512">
          <path
            d="M256 69.28C157.99 69.28 97.16 174.05 90.4 322.75L49.85 370.06V403.85H462.15V370.06L421.6 322.75C414.84 174.05 354.01 69.28 256 69.28Z"
            fill="#fff"
          />
          <circle cx="256" cy="32.11" r="32.11" fill="#fff" />
          <circle cx="256" cy="471.45" r="40.55" fill="#fff" />
          <path
            d="M128.25 257.12H193.55L216.26 197.5L253.16 319.57L275.87 257.12H383.75"
            fill="none"
            stroke="#000"
            strokeWidth="31.94"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </mask>
      </defs>
      <rect width="512" height="512" fill={color} mask={`url(#${id})`} />
    </svg>
  )
}

export function GitHubIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className={className} fill="currentColor">
      <path d="M12 .3a12 12 0 0 0-3.8 23.38c.6.12.83-.26.83-.57L9 21.07c-3.34.72-4.04-1.61-4.04-1.61-.55-1.39-1.34-1.76-1.34-1.76-1.08-.74.09-.73.09-.73 1.2.09 1.83 1.24 1.83 1.24 1.08 1.83 2.81 1.3 3.5 1 .1-.78.42-1.31.76-1.61-2.67-.3-5.47-1.33-5.47-5.93 0-1.31.47-2.38 1.24-3.22-.14-.3-.54-1.52.1-3.18 0 0 1-.32 3.3 1.23a11.5 11.5 0 0 1 6 0c2.28-1.55 3.29-1.23 3.29-1.23.64 1.66.24 2.88.12 3.18a4.65 4.65 0 0 1 1.23 3.22c0 4.61-2.8 5.63-5.48 5.92.42.36.81 1.1.81 2.22l-.01 3.29c0 .31.2.69.82.57A12 12 0 0 0 12 .3" />
    </svg>
  )
}
