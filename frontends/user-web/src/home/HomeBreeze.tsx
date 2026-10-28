import { Fragment, useEffect, useRef } from 'react';

// Fade to zero at the roots: a gust lifts the free ends leftward, not the face or sky.
const regions = [
  { name: 'hair', input: 'SourceGraphic', values: '.02;.105;.145;.09;.035;.02', delay: '0s', direction: 'rgb(90%,85%,50%)',
    shape: '<g clip-path="url(#hair-limit)"><path d="M720 305 Q810 195 935 140 Q1015 95 1080 128 L1045 195 Q1005 255 1015 330 Q925 340 850 318 Q790 310 735 335Z" fill="url(#roots)" filter="url(#soft)"/></g>' },
  { name: 'silk', input: 'hair', values: '.01;.055;.095;.075;.025;.01', delay: '-5.75s', direction: 'rgb(90%,65%,50%)',
    shape: '<path d="M240 500 Q580 365 805 345 Q990 370 1135 460 L1190 570 Q1020 690 700 800 L240 870Z" fill="url(#silk-roots)" filter="url(#soft)"/>' },
  { name: 'silk-ends', input: 'silk', values: '.015;.035;.095;.12;.05;.015', delay: '-5.45s', direction: 'rgb(90%,65%,50%)',
    shape: '<path d="M240 730 Q660 600 1050 590 L1210 710 L1500 1000 H240Z" fill="url(#silk-roots)" filter="url(#soft)"/>' },
].map(region => ({ ...region, mask: `data:image/svg+xml,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="1672" height="941" viewBox="0 0 1672 941"><defs><filter id="soft"><feGaussianBlur stdDeviation="48"/></filter><clipPath id="hair-limit"><rect width="1050" height="941"/></clipPath><linearGradient id="roots" gradientUnits="userSpaceOnUse" x1="750" x2="1050"><stop stop-color="white"/><stop offset=".45" stop-color="white" stop-opacity=".85"/><stop offset="1" stop-color="white" stop-opacity="0"/></linearGradient><linearGradient id="silk-roots" gradientUnits="userSpaceOnUse" x1="1120" y1="440" x2="320" y2="760"><stop stop-color="white" stop-opacity="0"/><stop offset=".2" stop-color="white" stop-opacity="0"/><stop offset="1" stop-color="white"/></linearGradient></defs>${region.shape}</svg>`)}` }));

export function HomeBreeze({ id, paused }: { id: string; paused: boolean }) {
  const svg = useRef<SVGSVGElement>(null);
  useEffect(() => {
    if (paused) svg.current?.pauseAnimations?.();
    else svg.current?.unpauseAnimations?.();
  }, [paused]);

  return <svg ref={svg} className="home-breeze" width="0" height="0" aria-hidden="true" focusable="false" style={{ position: 'absolute', pointerEvents: 'none' }}>
    <defs><filter id={id} x="0" y="0" width="1" height="1" primitiveUnits="objectBoundingBox" colorInterpolationFilters="sRGB">
      <feFlood floodColor="rgb(50%,50%,50%)" result="still" />
      {regions.map(region => <Fragment key={region.name}>
        <feFlood floodColor={region.direction} result={`${region.name}-wind`} />
        <feImage href={region.mask} x="0" y="0" width="1" height="1" preserveAspectRatio="none" result={`${region.name}-mask`} />
        <feComposite in={`${region.name}-wind`} in2={`${region.name}-mask`} operator="in" result={`${region.name}-motion`} />
        <feComposite in={`${region.name}-motion`} in2="still" operator="over" result={`${region.name}-field`} />
        <feDisplacementMap in={region.input} in2={`${region.name}-field`} scale="0" xChannelSelector="R" yChannelSelector="G" result={region.name}>
          <animate attributeName="scale" values={gustFrames(region.values)} begin={region.delay} dur="6s" repeatCount="indefinite" calcMode="discrete" />
        </feDisplacementMap>
      </Fragment>)}
      {/* Keep untouched pixels exact even when the browser rounds the neutral map color. */}
      <feMerge result="motion-envelope">{regions.map(region => <feMergeNode key={region.name} in={`${region.name}-mask`} />)}</feMerge>
      <feComponentTransfer in="motion-envelope" result="moving-area"><feFuncA type="linear" slope="40" /></feComponentTransfer>
      <feComposite in="silk-ends" in2="moving-area" operator="in" result="moving-art" />
      <feComposite in="SourceGraphic" in2="moving-area" operator="out" result="fixed-art" />
      <feComposite in="moving-art" in2="fixed-art" operator="arithmetic" k2="1" k3="1" />
    </filter></defs>
  </svg>;
}

// Limit full-image filter repaints to 24 fps, with eased motion between gust strengths.
function gustFrames(values: string) {
  const strengths = values.split(';').map(Number);
  const times = [0, .2, .36, .56, .8, 1];
  return Array.from({ length: 144 }, (_, frame) => {
    const progress = frame / 143;
    const segment = times.findIndex((end, index) => index > 0 && progress <= end) - 1;
    const t = (progress - times[segment]) / (times[segment + 1] - times[segment]);
    return (strengths[segment] + (strengths[segment + 1] - strengths[segment]) * t * t * (3 - 2 * t)).toFixed(5);
  }).join(';');
}
