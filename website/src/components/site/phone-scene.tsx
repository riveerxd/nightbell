"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import { Canvas, useFrame, useThree } from "@react-three/fiber"
import type { MotionValue } from "motion/react"
import * as THREE from "three"

/*
  The hero phone, as an object rather than a picture of one. The three screens
  are frames cut from the real alarm recording, so what the phone shows is what
  the app shows: the list at rest, the lockscreen card when the check fails, and
  the full-screen page. Scroll drives one number, `progress`, and every pose
  below is a pure function of it, eased toward each frame so a fast flick still
  reads as a motion and not a cut. Once it rings it keeps ringing until the
  Acknowledge button on the page is pressed, and then it shows a fourth
  capture, the same lock screen with nothing left on it.
*/

// a bezel of about two percent of the width, as on a current phone
const BODY = { w: 0.748, h: 1.623, r: 0.098, d: 0.07 }
// the captures are 1080 by 2400, so the screen is that shape exactly
const SCREEN = { w: 0.716, h: 1.591, r: 0.079 }
const RED = new THREE.Color("#ff5c66")
// what the calm screen throws on the room: near white, because a blue one turned
// the whole lighter body blue before anything had happened
const CALM = new THREE.Color("#d6deee")

function rounded(w: number, h: number, r: number) {
  const s = new THREE.Shape()
  const x = -w / 2
  const y = -h / 2
  s.moveTo(x + r, y)
  s.lineTo(x + w - r, y)
  s.quadraticCurveTo(x + w, y, x + w, y + r)
  s.lineTo(x + w, y + h - r)
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h)
  s.lineTo(x + r, y + h)
  s.quadraticCurveTo(x, y + h, x, y + h - r)
  s.lineTo(x, y + r)
  s.quadraticCurveTo(x, y, x + r, y)
  return s
}

/* ShapeGeometry hands out UVs in world units; a texture wants 0 to 1. */
function screenGeometry(w: number, h: number, r: number) {
  const g = new THREE.ShapeGeometry(rounded(w, h, r), 24)
  const pos = g.attributes.position
  const uv = g.attributes.uv
  for (let i = 0; i < pos.count; i++) uv.setXY(i, (pos.getX(i) + w / 2) / w, (pos.getY(i) + h / 2) / h)
  uv.needsUpdate = true
  return g
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v))
const span = (p: number, a: number, b: number) => clamp01((p - a) / (b - a))
const smooth = (t: number) => t * t * (3 - 2 * t)
const lerp = (a: number, b: number, t: number) => a + (b - a) * t

function useTextures(onFail: () => void) {
  // a frame on demand needs a nudge when the pixels arrive, or a page loaded
  // part way down would never draw them
  const invalidate = useThree((st) => st.invalidate)
  const gl = useThree((st) => st.gl)
  return useMemo(() => {
    const loader = new THREE.TextureLoader()
    // The full 1440 by 3200 captures. With the 1080 ones the phone filled about
    // half the texture's height on a laptop, so the GPU read a half-size mipmap
    // and the screen came out visibly softer than the page around it.
    return ["/hero/calm-hd.webp", "/hero/lock-hd.webp", "/hero/alarm-hd.webp", "/hero/quiet-hd.webp"].map((src) => {
      const t = loader.load(src, () => invalidate(), undefined, onFail)
      t.colorSpace = THREE.SRGBColorSpace
      t.anisotropy = Math.min(16, gl.capabilities.getMaxAnisotropy())
      return t
    })
  }, [invalidate, gl, onFail])
}

/*
  Reads the screen half a mipmap level sharper than the GPU would pick. Its
  choice is right for photographs and too soft for text, which is all a
  screenshot is.
*/
function sharpen(shader: THREE.WebGLProgramParametersWithUniforms) {
  shader.fragmentShader = shader.fragmentShader.replace(
    "#include <map_fragment>",
    "vec4 sampledDiffuseColor = texture2D( map, vMapUv, -0.6 );\ndiffuseColor *= sampledDiffuseColor;"
  )
}

/* The body follows the page: graphite on the dark page, near black on the light one. */
function useDarkPage() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains("dark"))
  useEffect(() => {
    const root = document.documentElement
    const mo = new MutationObserver(() => setDark(root.classList.contains("dark")))
    mo.observe(root, { attributes: true, attributeFilter: ["class"] })
    return () => mo.disconnect()
  }, [])
  return dark
}

// the camera below sees this much of the plane the phone rests on
const HALF_VIEW = 5.4 * Math.tan((15 * Math.PI) / 180)

function Phone({
  progress,
  mobile,
  restTop,
  acked,
  onReady,
  onFail,
}: {
  progress: MotionValue<number>
  mobile: boolean
  restTop: number | null
  acked: boolean
  onReady: () => void
  onFail: () => void
}) {
  const told = useRef(false)
  const group = useRef<THREE.Group>(null)
  const lock = useRef<THREE.MeshBasicMaterial>(null)
  const alarm = useRef<THREE.MeshBasicMaterial>(null)
  const quiet = useRef<THREE.MeshBasicMaterial>(null)
  const spill = useRef<THREE.PointLight>(null)
  const pool = useRef<THREE.MeshBasicMaterial>(null)
  const shadow = useRef<THREE.Mesh>(null)
  const size = useThree((st) => st.size)
  const [calmTex, lockTex, alarmTex, quietTex] = useTextures(onFail)
  const dark = useDarkPage()

  const body = useMemo(() => {
    const g = new THREE.ExtrudeGeometry(rounded(BODY.w - 0.024, BODY.h - 0.024, BODY.r - 0.012), {
      depth: BODY.d - 0.024,
      bevelEnabled: true,
      bevelThickness: 0.012,
      bevelSize: 0.012,
      bevelSegments: 5,
      curveSegments: 20,
    })
    g.center()
    return g
  }, [])
  const glass = useMemo(() => screenGeometry(BODY.w - 0.022, BODY.h - 0.022, BODY.r - 0.012), [])
  const screen = useMemo(() => screenGeometry(SCREEN.w, SCREEN.h, SCREEN.r), [])
  const poolTex = useMemo(() => {
    const c = document.createElement("canvas")
    c.width = c.height = 128
    const x = c.getContext("2d")!
    const g = x.createRadialGradient(64, 64, 0, 64, 64, 64)
    g.addColorStop(0, "rgba(255,255,255,1)")
    g.addColorStop(1, "rgba(255,255,255,0)")
    x.fillStyle = g
    x.fillRect(0, 0, 128, 128)
    return new THREE.CanvasTexture(c)
  }, [])

  const pose = useRef({ x: 0, y: 0, z: 0, rx: 0, ry: 0, rz: 0, s: 1 })
  const front = BODY.d / 2 + 0.0015

  const invalidate = useThree((st) => st.invalidate)
  // Frames are drawn on demand: when the scroll moves, and for as long after as
  // the phone is still settling, floating or ringing. A phone that has been
  // acknowledged and has stopped moving costs nothing.
  useEffect(() => progress.on("change", () => invalidate()), [progress, invalidate])
  useEffect(() => invalidate(), [acked, dark, restTop, invalidate])

  useFrame((state, dt) => {
    const g = group.current
    if (!g) return
    if (!told.current && calmTex.image && lockTex.image && alarmTex.image && quietTex.image) {
      told.current = true
      onReady()
    }
    const p = progress.get()
    const t = state.clock.elapsedTime
    const aspect = size.width / size.height

    // where the phone rests beside the headline
    // On a phone it rises from just under the buttons, nearly centred and full
    // size: restTop is where they end, as a share of the screen's height, and
    // the phone's top edge goes there. Parked low and right instead, it left a
    // dead band under the buttons and showed only its top.
    const restS = 1.05
    // the tilt lifts the near top corner above the top edge's centre, by about
    // a sixth of a unit at this pose; without it that corner sat on the button
    const lift = 0.17
    const restY = restTop === null ? -0.84 : 0.1 + HALF_VIEW - restTop * 2 * HALF_VIEW - (BODY.h / 2) * restS - lift
    const rest = mobile
      ? { x: 0.14, y: restY, z: 0, rx: -0.1, ry: -0.42, rz: 0.1, s: restS }
      : { x: Math.min(1.25, aspect * 0.62), y: -0.08, z: 0, rx: -0.06, ry: -0.5, rz: 0.05, s: 1.05 }
    // facing the reader, in the middle, closer
    const face = { x: 0, y: mobile ? 0.16 : 0.02, z: mobile ? -0.2 : 0.9, rx: 0, ry: 0, rz: 0, s: 1 }
    // and then stepping aside for the words, still ringing
    const side = mobile
      ? { x: 0, y: 0.62, z: -1.1, rx: 0.04, ry: 0.12, rz: -0.03, s: 1 }
      : { x: Math.min(0.95, aspect * 0.42), y: 0, z: 0.55, rx: 0, ry: -0.22, rz: -0.02, s: 1 }

    const m = smooth(span(p, 0.1, 0.3))
    const a = smooth(span(p, 0.4, 0.5))
    const target = { ...rest }
    for (const k of Object.keys(target) as (keyof typeof target)[]) {
      target[k] = lerp(lerp(rest[k], face[k], m), side[k], a)
    }

    // idle float while it waits, and the buzz for as long as it rings: from the
    // alarm until it is acknowledged, the way the real one does
    const idle = 1 - m
    target.y += Math.sin(t * 0.9) * 0.035 * idle
    target.ry += Math.sin(t * 0.5) * 0.07 * idle
    const ring = acked ? 0 : span(p, 0.33, 0.37)
    // the motor runs in bursts, not one long hum
    const burst = Math.sin(t * 5) > 0 ? 1 : 0.15

    const k = 1 - Math.exp(-dt * 7)
    const c = pose.current
    for (const key of Object.keys(c) as (keyof typeof c)[]) c[key] = lerp(c[key], target[key], k)
    // The buzz goes on after the easing. Added to the target, it went through
    // the same smoothing as the pose, which ate nine tenths of an 11 Hz shake
    // and left the ringing phone looking still. On a phone it rings further
    // from the camera, so it shakes harder to look the same size.
    const buzz = ring * burst * (mobile ? 1.6 : 1)
    g.position.set(c.x + Math.sin(t * 70) * 0.012 * buzz, c.y + Math.sin(t * 62) * 0.005 * buzz, c.z)
    g.rotation.set(c.rx, c.ry, c.rz + Math.sin(t * 55) * 0.02 * buzz)
    g.scale.setScalar(c.s)
    const settling = Math.abs(c.x - target.x) + Math.abs(c.y - target.y) + Math.abs(c.z - target.z) + Math.abs(c.ry - target.ry) > 0.0005
    // Acknowledged, the page closes the way the app's does, back to the lock
    // screen with nothing left on it. A black layer over the alarm stood in
    // for the screen sleeping and read as the phone dimming by mistake.
    const q = quiet.current
    const quietTo = acked && smooth(span(p, 0.32, 0.36)) > 0.5 ? 1 : 0
    if (q) q.opacity = Math.abs(q.opacity - quietTo) < 0.002 ? quietTo : lerp(q.opacity, quietTo, 1 - Math.exp(-dt * 5))
    const calmAgain = q?.opacity ?? 0
    if (idle > 0.01 || ring > 0 || settling || calmAgain !== quietTo) invalidate()

    const lockAmt = smooth(span(p, 0.22, 0.28))
    const alarmAmt = smooth(span(p, 0.32, 0.36))
    if (lock.current) lock.current.opacity = lockAmt
    if (alarm.current) alarm.current.opacity = alarmAmt
    const red = alarmAmt * (1 - calmAgain)

    // the screen lights what is in front of it, and turns the room red until
    // the alarm is answered
    const tint = CALM.clone().lerp(RED, Math.max(lockAmt * 0.6 * (1 - calmAgain), red))
    if (spill.current) {
      spill.current.color.copy(tint)
      spill.current.intensity = 0.6 + red * 3.2 + ring * burst * Math.abs(Math.sin(t * 9)) * 2
    }
    if (pool.current) {
      pool.current.color.copy(tint)
      pool.current.opacity = 0.1 + red * 0.45
    }
    if (shadow.current) {
      shadow.current.position.set(c.x, -1.62 * (mobile ? 1.25 : 1), c.z * 0.5)
      ;(shadow.current.material as THREE.MeshBasicMaterial).opacity = 0.55 * (mobile ? 0.6 : 1)
    }
  })

  return (
    <>
      <group ref={group}>
        <mesh geometry={body} castShadow>
          {/* satin, not chrome: a mirror body picks up the studio and goes pale on a light page */}
          <meshStandardMaterial color={dark ? "#8b919e" : "#121318"} metalness={dark ? 0.25 : 0.35} roughness={dark ? 0.36 : 0.42} />
        </mesh>
        <mesh geometry={glass} position-z={front - 0.001}>
          <meshStandardMaterial color="#050506" roughness={0.06} metalness={0.3} />
        </mesh>
        <mesh geometry={screen} position-z={front}>
          <meshBasicMaterial map={calmTex} toneMapped={false} onBeforeCompile={sharpen} />
        </mesh>
        <mesh geometry={screen} position-z={front + 0.0006}>
          <meshBasicMaterial ref={lock} map={lockTex} toneMapped={false} transparent opacity={0} onBeforeCompile={sharpen} />
        </mesh>
        <mesh geometry={screen} position-z={front + 0.0012}>
          <meshBasicMaterial ref={alarm} map={alarmTex} toneMapped={false} transparent opacity={0} onBeforeCompile={sharpen} />
        </mesh>
        <mesh geometry={screen} position-z={front + 0.0018}>
          <meshBasicMaterial ref={quiet} map={quietTex} toneMapped={false} transparent opacity={0} onBeforeCompile={sharpen} />
        </mesh>
        {/* side keys */}
        <mesh position={[BODY.w / 2 + 0.003, 0.34, 0]}>
          <boxGeometry args={[0.012, 0.2, 0.034]} />
          <meshStandardMaterial color={dark ? "#9aa0ad" : "#1c1e24"} metalness={0.4} roughness={0.4} />
        </mesh>
        <mesh position={[BODY.w / 2 + 0.003, 0.08, 0]}>
          <boxGeometry args={[0.012, 0.11, 0.034]} />
          <meshStandardMaterial color={dark ? "#9aa0ad" : "#1c1e24"} metalness={0.4} roughness={0.4} />
        </mesh>
        <pointLight ref={spill} position={[0, 0, 0.5]} distance={4} decay={1.6} />
      </group>
      {/* light from the screen pooling on the surface under it */}
      <mesh rotation-x={-Math.PI / 2} position={[0, -1.6, 0.6]} scale={[3.2, 2.2, 1]}>
        <planeGeometry />
        <meshBasicMaterial ref={pool} map={poolTex} transparent depthWrite={false} blending={THREE.AdditiveBlending} opacity={0.1} />
      </mesh>
      {/* contact shadow: a soft dark ellipse that follows the phone */}
      <mesh ref={shadow} rotation-x={-Math.PI / 2} scale={[1.1, 0.34, 1]}>
        <planeGeometry />
        <meshBasicMaterial map={poolTex} color="#000" transparent depthWrite={false} opacity={0.55} />
      </mesh>
    </>
  )
}

export default function PhoneScene({
  progress,
  mobile,
  restTop = null,
  acked,
  onReady,
  onFail,
}: {
  progress: MotionValue<number>
  mobile: boolean
  restTop?: number | null
  acked: boolean
  onReady: () => void
  onFail: () => void
}) {
  const host = useRef<HTMLDivElement>(null)
  // The screen's own density, so the screenshot is as sharp as the page around
  // it. Any fraction of it is worse than it looks: a laptop at 2x held to 1.86x
  // by the old 4.5 million pixel budget had its whole canvas stretched by the
  // browser, and that blur sat on top of everything else. Only a screen past
  // nine million pixels a frame, a 4K monitor, is held back now.
  const [dpr] = useState(() => Math.max(1, Math.min(window.devicePixelRatio, 3, Math.sqrt(9e6 / (innerWidth * innerHeight)))))
  // nothing to draw once the hero has scrolled away, so the GPU is let go
  const [onScreen, setOnScreen] = useState(true)
  useEffect(() => {
    const io = new IntersectionObserver(([e]) => setOnScreen(e.isIntersecting))
    if (host.current) io.observe(host.current)
    return () => io.disconnect()
  }, [])
  return (
    <div ref={host} className="absolute inset-0">
      <Canvas
        dpr={dpr}
        gl={{ antialias: true, alpha: true, powerPreference: "high-performance" }}
        camera={{ fov: 30, position: [0, 0.1, 5.4] }}
        onCreated={({ gl }) => {
          gl.toneMapping = THREE.ACESFilmicToneMapping
          gl.toneMappingExposure = 1.05
          // Phones drop a context under memory pressure, often while the tab
          // sits in the background. Restoring it means rebuilding every texture
          // at the worst moment, so the hero takes the picture phone instead.
          gl.domElement.addEventListener("webglcontextlost", onFail, { once: true })
        }}
        frameloop={onScreen ? "demand" : "never"}
      >
        {/* Plain lights rather than a generated studio environment: building that
            environment was the single most expensive thing on the page, and a
            satin body barely reflects it. */}
        <hemisphereLight args={["#e8eeff", "#0a0b10", 0.9]} />
        <ambientLight intensity={0.2} />
        <directionalLight position={[2.5, 3, 2]} intensity={1.4} />
        {/* a rim from behind-left gives the edge a line to read against the dark.
            It was brand blue until the body went lighter on the dark page; then
            it painted the whole edge blue, so it is a cool white now. */}
        <directionalLight position={[-3, 1.5, -2]} intensity={1.6} color="#dfe6f5" />
        <Phone progress={progress} mobile={mobile} restTop={restTop} acked={acked} onReady={onReady} onFail={onFail} />
      </Canvas>
    </div>
  )
}
