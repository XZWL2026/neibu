import { useEffect, useRef, useState } from 'react';

const vertex = `attribute vec2 position;
void main() { gl_Position = vec4(position, 0., 1.); }`;
const fragment = `precision highp float;
uniform vec2 resolution;
uniform sampler2D surface;
uniform sampler2D earthSurface;
uniform sampler2D nightSurface;
uniform float moonSpin;
uniform float moonOpacity;
uniform float spin;
uniform vec3 moon;

vec4 planet(vec2 pixel, vec2 center, float radius, float angle, bool small) {
  vec2 p = (pixel - center) / radius;
  float distance = length(p);
  float atmosphere = small ? .035 : .065;
  float sunrise = pow(max(dot(normalize(vec2(p.x,-p.y)),normalize(vec2(.4,.916))),0.),100.);
  if (distance > 1. + atmosphere) return vec4(0.);
  if (distance > 1.) {
    if (small) return vec4(.27,.52,.93,pow(1.-(distance-1.)/atmosphere,3.)*.6);
    float height = distance-1.;
    float halo = exp(-height*85.) * (1.-smoothstep(.025,.065,height));
    float edge = exp(-height*850.);
    vec3 sky = mix(vec3(.19,.42,.85),vec3(.76,.88,1.),edge);
    sky = mix(sky,vec3(.82,.91,1.),sunrise*exp(-height*95.)*.75);
    return vec4(sky, min(.96,halo*(.46+sunrise*.5)+edge*.4));
  }
  vec3 normal = vec3(p.x, -p.y, sqrt(max(0., 1. - dot(p,p))));
  // Tilt the axis toward the camera: the visible cap shows land, not only the pole.
  float tilt = .95;
  vec3 tilted = vec3(normal.x, normal.y*cos(tilt)-normal.z*sin(tilt), normal.y*sin(tilt)+normal.z*cos(tilt));
  float longitude = small ? .56 : .7;
  vec2 uv = vec2(fract(longitude + (atan(tilted.x,tilted.z)+angle)/6.2831853), .5-asin(tilted.y)/3.1415927);
  vec3 color = small ? texture2D(surface, uv).rgb : texture2D(earthSurface, uv).rgb;
  vec3 light = small ? normalize(vec3(1., .25, -.45)) : normalize(vec3(.4, .8, .2));
  float day = max(dot(normal,light),0.);
  if (small) {
    color *= .035 + day*.65;
    color += vec3(.28,.55,1.) * pow(1.-normal.z,4.) * day*1.8;
  } else {
    // Keep real cloud/coast detail in the dark hemisphere, with observed city lights.
    vec3 night = texture2D(nightSurface,uv).rgb;
    float light = max(night.r,max(night.g,night.b));
    float cities = smoothstep(.16,.65,light)*light;
    float limb = pow(1.-normal.z,4.);
    color *= vec3(.36,.52,.8) * (.18+limb*.2);
    color += vec3(1.,.68,.36)*cities*.85;
    color += vec3(.2,.42,.8)*limb*.28;
    float edge = pow(1.-normal.z,30.);
    color += vec3(.6,.8,1.)*edge*(.75+sunrise*.8);
    color += vec3(.36,.57,.88)*pow(1.-normal.z,12.)*sunrise*.35;
  }
  return vec4(color,1.);
}
void main() {
  // The same cover projection as the fixed 1672x941 background.
  vec2 source = vec2(1672.,941.);
  float scale = max(resolution.x/source.x,resolution.y/source.y);
  vec2 pixel = (vec2(gl_FragCoord.x,resolution.y-gl_FragCoord.y)-resolution*.5)/scale+source*.5;
  vec4 satellite = planet(pixel,moon.xy,moon.z,moonSpin,true);
  satellite.a *= moonOpacity;
  vec4 earth = planet(pixel,vec2(-45.,2153.),1732.,spin,false);
  float alpha = earth.a + satellite.a*(1.-earth.a);
  vec3 rgb = (earth.rgb*earth.a + satellite.rgb*satellite.a*(1.-earth.a))/max(alpha,.001);
  // The reference sunrise stays fixed on the horizon while the surface turns beneath it.
  vec2 sunrise = vec2(-45.,2153.) + normalize(vec2(.4,-.916))*1732.;
  float radius2 = dot(pixel-sunrise,pixel-sunrise);
  float flare = exp(-radius2/4400.)*.28 + exp(-radius2/180.)*.85;
  rgb = (rgb*alpha + vec3(.68,.82,1.)*flare)/max(alpha+flare,.001);
  gl_FragColor = vec4(rgb,min(1.,alpha+flare));
}`;

export function LoginSpace() {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [ready, setReady] = useState(false);
  const [reduced, setReduced] = useState(() => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false);
  useEffect(() => {
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    const change = () => setReduced(media?.matches ?? false);
    media?.addEventListener('change',change);
    return () => media?.removeEventListener('change',change);
  }, []);
  useEffect(() => {
    setReady(false);
    if (reduced) return;
    const element = canvas.current!;
    const gl = element.getContext('webgl',{alpha:true,premultipliedAlpha:false});
    if (!gl) return;
    const program = gl.createProgram();
    if (!program) return;
    const shaders: WebGLShader[] = [];
    for (const [type,source] of [[gl.VERTEX_SHADER,vertex],[gl.FRAGMENT_SHADER,fragment]] as const) {
      const shader = gl.createShader(type);
      if (!shader) break;
      shaders.push(shader); gl.shaderSource(shader,source); gl.compileShader(shader);
      if (!gl.getShaderParameter(shader,gl.COMPILE_STATUS)) break;
      gl.attachShader(program,shader);
    }
    gl.linkProgram(program);
    const buffer = gl.createBuffer(), texture = gl.createTexture(), earthTexture = gl.createTexture(), nightTexture = gl.createTexture();
    const release = () => { gl.deleteBuffer(buffer); gl.deleteTexture(texture); gl.deleteTexture(earthTexture); gl.deleteTexture(nightTexture); shaders.forEach(shader=>gl.deleteShader(shader)); gl.deleteProgram(program); };
    if (!gl.getProgramParameter(program,gl.LINK_STATUS) || !buffer || !texture || !earthTexture || !nightTexture) {release();return;}
    gl.useProgram(program); gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]),gl.STATIC_DRAW);
    const position = gl.getAttribLocation(program,'position');
    gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position,2,gl.FLOAT,false,0,0);
    const resolution = gl.getUniformLocation(program,'resolution');
    const spin = gl.getUniformLocation(program,'spin');
    const moonSpin = gl.getUniformLocation(program,'moonSpin');
    const moonOpacity = gl.getUniformLocation(program,'moonOpacity');
    const moon = gl.getUniformLocation(program,'moon');
    gl.uniform1i(gl.getUniformLocation(program,'surface'),0);
    gl.uniform1i(gl.getUniformLocation(program,'earthSurface'),1);
    gl.uniform1i(gl.getUniformLocation(program,'nightSurface'),2);
    let frame=0, loaded=0, disposed=false, lastDraw=-Infinity, started=0;
    const draw = (now: number) => {
      if (disposed || loaded!==4 || document.hidden) return;
      // Decorative animation needs only 30fps and a bounded retina backing buffer.
      if (now-lastDraw>=32) {
        lastDraw=now;
        const ratio=Math.min(window.devicePixelRatio||1,1.5);
        const width=Math.max(1,Math.round(element.clientWidth*ratio));
        const height=Math.max(1,Math.round(element.clientHeight*ratio));
        if (element.width!==width || element.height!==height) {element.width=width;element.height=height;}
        gl.viewport(0,0,width,height); gl.uniform2f(resolution,width,height);
        const elapsed=now-started;
        gl.uniform1f(moonSpin,(elapsed%120000)/120000*Math.PI*2);
        gl.uniform1f(spin,(elapsed%600000)/600000*Math.PI*2);
        const orbitSpeed=Math.PI*2/400000;
        // Match the shader's cover crop, so returning never waits outside the window.
        const scale=Math.max(width/1672,height/941);
        const left=836-width/scale/2, right=836+width/scale/2;
        const bottom=470.5+height/scale/2;
        const start=-Math.acos((Math.max(345,left+100)+45)/1962);
        const end=Math.min(-Math.acos(Math.min(1,(right+85+45)/1962)),Math.asin((bottom+85-2153)/1962));
        const phase=elapsed%((end-start)/orbitSpeed);
        const orbit=start+phase*orbitSpeed;
        gl.uniform1f(moonOpacity,Math.min(1,phase/800));
        gl.uniform3f(moon,-45+1962*Math.cos(orbit),2153+1962*Math.sin(orbit),85);
        gl.drawArrays(gl.TRIANGLES,0,6);
      }
      frame=requestAnimationFrame(draw);
    };
    const redraw=()=>{cancelAnimationFrame(frame);lastDraw=-Infinity;draw(performance.now());};
    const surface=new Image(), earth=new Image(), night=new Image(), stars=new Image();
    const onLoad=()=>{if(disposed)return;loaded++;if(loaded===4){started=performance.now();setReady(true);redraw();}};
    for (const [image,target,unit] of [[surface,texture,gl.TEXTURE0],[earth,earthTexture,gl.TEXTURE1],[night,nightTexture,gl.TEXTURE2]] as const) {
      image.onload=()=>{
        if(disposed)return;
        gl.activeTexture(unit);gl.bindTexture(gl.TEXTURE_2D,target);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
        gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,image);onLoad();
      };
    }
    stars.onload=onLoad;
    surface.src='/auth/earth-surface.png';earth.src='/auth/earth-blue-marble.jpg';night.src='/auth/earth-night.jpg';stars.src='/auth/orbit-stars.png';
    const resize=new ResizeObserver(redraw);resize.observe(element);
    const lost=(event: Event)=>{event.preventDefault();loaded=0;cancelAnimationFrame(frame);setReady(false);};
    element.addEventListener('webglcontextlost',lost);
    document.addEventListener('visibilitychange',redraw);
    return ()=>{
      disposed=true;surface.onload=null;earth.onload=null;night.onload=null;stars.onload=null;cancelAnimationFrame(frame);resize.disconnect();
      element.removeEventListener('webglcontextlost',lost);document.removeEventListener('visibilitychange',redraw);release();
    };
  }, [reduced]);
  return <div className={`auth-scenery${ready?' is-animated':''}`} aria-hidden="true"><div className="auth-meteors"><i /><i /></div><canvas ref={canvas} /></div>;
}
