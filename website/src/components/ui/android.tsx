"use client"

import { useId, type ReactNode, type SVGProps } from "react"

import { cn } from "@/lib/utils"

/*
  Magic UI's Android frame, repaired. The registry version drew a 378 wide
  phone inside a 433 wide viewBox, placed the screen image at 0,0 while the clip
  sat at 9,14, painted the camera underneath the screenshot, and hard coded one
  clipPath id for every phone on the page. The geometry below is the same frame
  with those four things fixed, plus an HTML slot for screens that are live
  markup rather than a picture.
*/
const W = 380
const H = 830
const SCREEN = { x: 9, y: 14, w: 360, h: 800, rx: 33, ry: 25 }

export interface AndroidProps extends Omit<SVGProps<SVGSVGElement>, "children"> {
  src?: string
  videoSrc?: string
  alt?: string
  children?: ReactNode
  frameClassName?: string
}

export function Android({
  src,
  videoSrc,
  alt,
  children,
  className,
  frameClassName,
  ...props
}: AndroidProps) {
  const clip = useId().replace(/:/g, "")
  return (
    <div className={cn("relative aspect-[380/830] w-full", className)}>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        className={cn("absolute inset-0 size-full", frameClassName)}
        role={alt ? "img" : undefined}
        aria-label={alt}
        aria-hidden={alt ? undefined : true}
        {...props}
      >
        <path
          d="M376 153H378C379.105 153 380 153.895 380 155V249C380 250.105 379.105 251 378 251H376V153Z"
          className="fill-[#d4d7dd] dark:fill-[#2a2e36]"
        />
        <path
          d="M376 301H378C379.105 301 380 301.895 380 303V351C380 352.105 379.105 353 378 353H376V301Z"
          className="fill-[#d4d7dd] dark:fill-[#2a2e36]"
        />
        <path
          d="M0 42C0 18.8041 18.804 0 42 0H336C359.196 0 378 18.804 378 42V788C378 811.196 359.196 830 336 830H42C18.804 830 0 811.196 0 788V42Z"
          className="fill-[#d4d7dd] dark:fill-[#2a2e36]"
        />
        <path
          d="M2 43C2 22.0132 19.0132 5 40 5H338C358.987 5 376 22.0132 376 43V787C376 807.987 358.987 825 338 825H40C19.0132 825 2 807.987 2 787V43Z"
          className="fill-[#f2f3f5] dark:fill-[#15171c]"
        />
        <rect
          x={SCREEN.x}
          y={SCREEN.y}
          width={SCREEN.w}
          height={SCREEN.h}
          rx={SCREEN.rx}
          ry={SCREEN.ry}
          className="fill-black"
        />
        {src && (
          <image
            href={src}
            x={SCREEN.x}
            y={SCREEN.y}
            width={SCREEN.w}
            height={SCREEN.h}
            preserveAspectRatio="xMidYMid slice"
            clipPath={`url(#${clip})`}
          />
        )}
        {videoSrc && (
          <foreignObject
            x={SCREEN.x}
            y={SCREEN.y}
            width={SCREEN.w}
            height={SCREEN.h}
            clipPath={`url(#${clip})`}
          >
            <video
              className="size-full object-cover"
              src={videoSrc}
              autoPlay
              loop
              muted
              playsInline
            />
          </foreignObject>
        )}
        <circle cx="189" cy="28" r="6" className="fill-black" />
        <circle cx="189" cy="28" r="2.5" className="fill-[#1b1e24]" />
        <defs>
          <clipPath id={clip}>
            <rect
              x={SCREEN.x}
              y={SCREEN.y}
              width={SCREEN.w}
              height={SCREEN.h}
              rx={SCREEN.rx}
              ry={SCREEN.ry}
            />
          </clipPath>
        </defs>
      </svg>
      {children && (
        <div
          className="absolute overflow-hidden"
          style={{
            left: `${(SCREEN.x / W) * 100}%`,
            top: `${(SCREEN.y / H) * 100}%`,
            width: `${(SCREEN.w / W) * 100}%`,
            height: `${(SCREEN.h / H) * 100}%`,
            borderRadius: `${(SCREEN.rx / SCREEN.w) * 100}% / ${(SCREEN.ry / SCREEN.h) * 100}%`,
            containerType: "inline-size",
          }}
        >
          {children}
        </div>
      )}
    </div>
  )
}
