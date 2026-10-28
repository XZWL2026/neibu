/* Adapted from the user-provided React Bits CircularCarousel (orbit preset).
   Keeps its 3D projection, spring, intro, inertia, depth shading and captions.
   Native DOM adapter keeps this page usable offline without a React build. */
export function createCarousel(root, items, options, scope) {
const requestAnimationFrame = scope.frame;
const cancelAnimationFrame = scope.cancelFrame;
  'use strict';
  const clamp=(v,a,b)=>Math.min(b,Math.max(a,v));
  const wrap=d=>((((d+180)%360)+360)%360)-180;
  const rad=Math.PI/180,step=72,SPRING=118;
  const rotateX=(p,d)=>{const c=Math.cos(d*rad),s=Math.sin(d*rad);return [p[0],p[1]*c-p[2]*s,p[1]*s+p[2]*c];};
  const rotateY=(p,d)=>{const c=Math.cos(d*rad),s=Math.sin(d*rad);return [p[0]*c+p[2]*s,p[1],-p[0]*s+p[2]*c];};
  class CircularCarousel {
    constructor(root,items,{onChange,onActivate,language='zh'}={}) {
      this.root=root;this.items=items;this.onChange=onChange;this.onActivate=onActivate;this.language=language;
      this.media=matchMedia('(prefers-reduced-motion: reduce)');this.reduced=this.media.matches;
      this.visible=true;this.raf=0;this.active=-1;this.ready=false;this.manualPause=false;
      this.state={angle:-144,velocity:0,target:null,dir:-1,press:null,drag:false,hover:false,pointer:{inside:false,x:0,y:0},yaw:0,pitch:0,intro:null,introDone:false,holdUntil:0,fit:1,shift:0,drop:0,last:0,suppressClick:false};
      root.classList.add('circular-carousel');root.dataset.axis='y';root.dataset.shape='orbit';root.dataset.draggable='';
      root.tabIndex=0;root.setAttribute('role','region');root.setAttribute('aria-roledescription','carousel');root.setAttribute('aria-label','功能卡片环形轮播');
      this.stage=root.querySelector('.circular-carousel__stage');this.camera=root.querySelector('.circular-carousel__camera');this.ring=root.querySelector('.circular-carousel__ring');
      this.cards=[...root.querySelectorAll('.circular-carousel__card')];
      this.setLanguage(language);this.measure();this.render(performance.now());this.bind();
      Promise.all(this.cards.map(card=>{
        const image=card.querySelector('.commerce-art');
        const loaded=image.complete?Promise.resolve():new Promise(resolve=>{scope.listen(image,'load',resolve,{once:true});scope.listen(image,'error',resolve,{once:true});});
        return loaded.then(()=>image.decode?image.decode().catch(()=>{}):undefined);
      })).then(()=>this.start());
    }
    start(){if(!this.root.isConnected||this.ready)return;this.ready=true;this.root.dataset.ready='';this.wake();}
    measure(){
      const rect=this.root.getBoundingClientRect();if(!rect.width||!rect.height)return;
      const mobile=rect.width<650;
      // A broad, shallow orbit fits the existing page. Mobile keeps a readable front card.
      const cardW=mobile?224:clamp(rect.width*.225,240,360),cardH=cardW/.75;
      const gap=mobile?28:54,perspective=mobile?1500:1900,tilt=mobile?-5:-7;
      const pitch=(cardW+gap)*1.45,radius=pitch/(2*Math.sin(Math.PI/5));
      this.settings={cardW,cardH,radius,perspective,tilt,speed:9,momentum:.48,parallax:.16,stretch:.16,depthFade:.38};
      const corners=[[-cardW/2,-cardH/2],[cardW/2,-cardH/2],[-cardW/2,cardH/2],[cardW/2,cardH/2]];
      let minX=Infinity,maxX=-Infinity,minY=Infinity,maxY=-Infinity;
      for(let a=-180;a<=180;a+=7.5)for(const [cx,cy] of corners){
        const c=rotateY([0,0,radius],a),p=rotateX([c[0]+cx,cy,c[2]],tilt);p[2]-=radius;
        const k=perspective/(perspective-p[2]);minX=Math.min(minX,p[0]*k);maxX=Math.max(maxX,p[0]*k);minY=Math.min(minY,p[1]*k);maxY=Math.max(maxY,p[1]*k);
      }
      const room=88,fit=mobile?Math.min(1,(rect.height-room)*.92/(maxY-minY)):Math.min(1,rect.width*.97/(maxX-minX),(rect.height-room)*.95/(maxY-minY));
      this.state.fit=fit;this.state.shift=-((minY+maxY)/2)*fit-room/2;this.state.drop=(rect.height/fit)*.55+cardH;
      this.stage.style.perspective=`${perspective}px`;this.stage.style.transform=`translate3d(0,${this.state.shift}px,0) scale(${fit})`;
      this.cards.forEach(card=>{const frame=card.firstElementChild;Object.assign(frame.style,{left:`${-cardW/2}px`,top:`${-cardH/2}px`,width:`${cardW}px`,height:`${cardH}px`});});
      this.wake();
    }
    advance(dt,now){
      const s=this.state,c=this.settings;
      if(!s.introDone&&this.ready){
        if(this.reduced)s.introDone=true;
        else if(!s.intro)s.intro={start:now};
        if(s.intro&&now-s.intro.start>=1400){s.intro=null;s.introDone=true;}
      }
      const paused=s.hover||s.focus||s.drag||this.manualPause||this.reduced||now<s.holdUntil;
      const cruise=!paused&&!s.intro?c.speed*s.dir:0;
      let busy=!!s.intro||s.drag;
      if(s.drag||s.intro){if(s.intro)s.velocity=0;}
      else if(s.target!==null){
        let remaining=dt;while(remaining>0){const h=Math.min(remaining,1/240);s.velocity+=(SPRING*(s.target-s.angle)-2*Math.sqrt(SPRING)*s.velocity)*h;s.angle+=s.velocity*h;remaining-=h;}
        if(Math.abs(s.target-s.angle)<.004&&Math.abs(s.velocity)<.03){s.angle=s.target;s.velocity=0;s.target=null;}
        busy=true;
      }else{
        const tau=.18+c.momentum*1.5;s.velocity+=(cruise-s.velocity)*(1-Math.exp(-dt/tau));s.angle+=s.velocity*dt;
        if(cruise===0&&Math.abs(s.velocity)<9){const target=Math.round(s.angle/step)*step;if(Math.abs(target-s.angle)>.004)s.target=target;else{s.angle=target;s.velocity=0;}}
        busy=busy||cruise!==0||Math.abs(s.velocity)>.01||s.target!==null;
      }
      if(now<s.holdUntil)busy=true;
      const ease=1-Math.exp(-dt/.35),p=this.reduced?0:c.parallax;
      const yaw=s.pointer.inside?s.pointer.x*p*9:0,pitch=s.pointer.inside?-s.pointer.y*p*6:0;
      s.yaw+=(yaw-s.yaw)*ease;s.pitch+=(pitch-s.pitch)*ease;
      if(Math.abs(yaw-s.yaw)>.01||Math.abs(pitch-s.pitch)>.01)busy=true;
      return busy;
    }
    render(now){
      const s=this.state,c=this.settings,R=c.radius*(1+(this.reduced?0:c.stretch)*.12*Math.min(1,Math.abs(s.velocity)/420));
      this.camera.style.transform=`translate3d(0,0,${-R}px) rotateX(${c.tilt+s.pitch}deg) rotateY(${s.yaw}deg)`;
      this.ring.style.transform=`rotateY(${s.angle}deg)`;
      this.cards.forEach((card,i)=>{
        const base=i*step,reach=Math.abs(wrap(base+s.angle)),delay=reach/180*480;
        const progress=s.intro?1-Math.pow(1-clamp((now-s.intro.start-delay)/900,0,1),5):1;
        const lift=(1-progress)*s.drop;
        card.style.transform=`rotateY(${base}deg) translateZ(${R}px) rotateY(${-(base+s.angle)}deg)${lift?` translateY(${lift}px)`:''}`;
        card.style.setProperty('--cc-depth',(c.depthFade*Math.pow((1-Math.cos((base+s.angle)*rad))/2,1.25)).toFixed(3));
      });
      const index=((Math.round(-s.angle/step)%5)+5)%5;
      if(index!==this.active){this.active=index;this.updateCaption();this.cards.forEach((card,i)=>{card.dataset.active=String(i===index);card.firstElementChild.dataset.active=String(i===index);});this.onChange?.(index);}
      this.root.dataset.settled=String(!s.intro&&s.target===null&&!s.drag);
    }
    wake(){if(!this.raf&&this.visible&&!document.hidden)this.raf=requestAnimationFrame(now=>this.frame(now));}
    frame(now){this.raf=0;const dt=this.state.last?Math.min((now-this.state.last)/1000,.05):1/60;this.state.last=now;const busy=this.advance(dt,now);this.render(now);if(busy)this.wake();else this.state.last=0;}
    focusIndex(index){
      index=((index%5)+5)%5;const s=this.state;let target=-index*step;target+=360*Math.round((s.angle-target)/360);
      if(this.reduced){s.angle=target;s.velocity=0;s.target=null;this.render(performance.now());}else s.target=target;
      s.holdUntil=performance.now()+3200;this.wake();
    }
    stepBy(delta){const s=this.state,base=s.target??Math.round(s.angle/step)*step;this.focusIndex(Math.round(-base/step)+delta);}
    setLanguage(language){this.language=language;}
    updateCaption(){}
    togglePaused(){this.manualPause=!this.manualPause;this.wake();return this.manualPause;}
    getState(){const s=this.state;return {preset:'orbit',captions:true,active:this.active,angle:s.angle,target:s.target,velocity:s.velocity,dragging:s.drag,hover:s.hover,paused:this.manualPause,reduced:this.reduced,introDone:s.introDone,visible:this.visible,...this.settings,fit:s.fit};}
    bind(){
      const root=this.root,s=this.state;
      scope.listen(root,'focusin',()=>{s.focus=true;this.wake();});
      scope.listen(root,'focusout',e=>{s.focus=root.contains(e.relatedTarget);this.wake();});
      scope.listen(root,'pointerdown',e=>{s.suppressClick=false;if(e.button!==0)return;s.press={id:e.pointerId,x:e.clientX,y:e.clientY,angle:s.angle,moved:false,origin:0,samples:[{time:performance.now(),angle:s.angle}]};});
      scope.listen(root,'pointermove',e=>{
        const rect=root.getBoundingClientRect();if(e.pointerType==='mouse'){s.pointer.inside=true;s.pointer.x=clamp((e.clientX-rect.left)/rect.width*2-1,-1,1);s.pointer.y=clamp((e.clientY-rect.top)/rect.height*2-1,-1,1);}
        const p=s.press;if(!p||p.id!==e.pointerId){this.wake();return;}
        const delta=e.clientX-p.x,cross=e.clientY-p.y;
        if(!p.moved){if(Math.abs(delta)<5)return;if(Math.abs(cross)>Math.abs(delta)*1.2&&e.pointerType!=='mouse'){s.press=null;return;}p.moved=true;p.origin=delta;s.drag=true;s.target=null;s.velocity=0;root.dataset.dragging='';root.setPointerCapture(e.pointerId);}
        s.angle=p.angle+(delta-p.origin)*180/(Math.PI*this.settings.radius*s.fit);
        const now=performance.now();p.samples.push({time:now,angle:s.angle});while(p.samples.length>2&&now-p.samples[0].time>110)p.samples.shift();this.wake();
      });
      const release=e=>{
        const p=s.press;if(!p||p.id!==e.pointerId)return;s.press=null;if(!p.moved)return;
        s.drag=false;delete root.dataset.dragging;s.suppressClick=true;
        const first=p.samples[0],last=p.samples[p.samples.length-1],span=(last.time-first.time)/1000;
        const v=e.type==='pointercancel'||this.reduced?0:span>.008?clamp((last.angle-first.angle)/span,-720,720):0;
        s.velocity=v;if(Math.abs(v)>60)s.dir=Math.sign(v);
        const tau=.18+this.settings.momentum*1.5;s.target=Math.round((s.angle+v*tau*.55)/step)*step;s.holdUntil=performance.now()+2200;
        if(this.reduced){s.angle=s.target;s.target=null;this.render(performance.now());}
        this.wake();
      };
      scope.listen(root,'pointerup',release);scope.listen(root,'pointercancel',release);
      scope.listen(root,'lostpointercapture',e=>{if(s.press)release(e);});
      scope.listen(root,'pointerenter',e=>{if(e.pointerType==='mouse')s.hover=true;this.wake();});
      scope.listen(root,'pointerleave',e=>{if(e.pointerType==='mouse'){s.hover=false;s.pointer.inside=false;}this.wake();});
      scope.listen(root,'click',e=>{if(s.suppressClick){s.suppressClick=false;e.preventDefault();e.stopPropagation();return;}const card=e.target.closest('[data-cc-index]');if(!card)return;const index=Number(card.dataset.ccIndex);if(!e.metaKey&&!e.ctrlKey&&!e.shiftKey&&!e.altKey&&e.button===0){e.preventDefault();this.onActivate?.(index);}});
      scope.listen(root,'keydown',e=>{
        if(e.key==='ArrowRight')this.stepBy(1);else if(e.key==='ArrowLeft')this.stepBy(-1);else if(e.key==='Home')this.focusIndex(0);else if(e.key==='End')this.focusIndex(4);else if((e.key==='Enter'||e.key===' ')&&e.target===root)this.onActivate?.(this.active);else return;e.preventDefault();
      });
      scope.listen(root,'wheel',e=>{
        if(Math.abs(e.deltaX)<=Math.abs(e.deltaY)||!e.deltaX)return;e.preventDefault();const v=e.deltaX*180/(Math.PI*this.settings.radius*s.fit);s.target=null;s.angle-=v;s.velocity=-v*30;s.holdUntil=performance.now()+1600;
        clearTimeout(this.wheelTimer);this.wheelTimer=setTimeout(()=>{s.target=Math.round((s.angle+s.velocity*.12)/step)*step;this.wake();},140);this.wake();
      },{passive:false});
      this.resize=scope.resize(()=>this.measure());this.resize.observe(root);
      this.observer=scope.intersection(([entry])=>{this.visible=entry.isIntersecting;if(this.visible)this.wake();else{cancelAnimationFrame(this.raf);this.raf=0;s.last=0;}});this.observer.observe(root);
      scope.listen(document,'visibilitychange',()=>{if(document.hidden){cancelAnimationFrame(this.raf);this.raf=0;s.last=0;}else this.wake();});
      scope.listen(this.media,'change',()=>{this.reduced=this.media.matches;if(this.reduced){s.intro=null;s.introDone=true;s.target=null;s.velocity=0;s.angle=Math.round(s.angle/step)*step;}this.wake();});
    }
  }
  const carousel = new CircularCarousel(root, items, options);
  scope.onDispose(() => clearTimeout(carousel.wheelTimer));
  return carousel;
}
