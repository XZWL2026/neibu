import cakeSmall from '../assets/commerce/optimized/cake-400.webp';
import cakeLarge from '../assets/commerce/optimized/cake-800.webp';
import jewelrySmall from '../assets/commerce/optimized/jewelry-400.webp';
import jewelryLarge from '../assets/commerce/optimized/jewelry-800.webp';
import shoesSmall from '../assets/commerce/optimized/shoes-400.webp';
import shoesLarge from '../assets/commerce/optimized/shoes-800.webp';
import headphonesSmall from '../assets/commerce/optimized/headphones-400.webp';
import headphonesLarge from '../assets/commerce/optimized/headphones-800.webp';
import livingroomSmall from '../assets/commerce/optimized/livingroom-400.webp';
import livingroomLarge from '../assets/commerce/optimized/livingroom-800.webp';
import portraitSmall from '../assets/commerce/optimized/portrait-400.webp';
import portraitLarge from '../assets/commerce/optimized/portrait-800.webp';
import figtartSmall from '../assets/commerce/optimized/figtart-400.webp';
import figtartLarge from '../assets/commerce/optimized/figtart-800.webp';

export const commerceInspirationImages = {
  cake: { src: cakeLarge, srcSet: `${cakeSmall} 400w, ${cakeLarge} 800w`, width: 800, height: 800 },
  jewelry: { src: jewelryLarge, srcSet: `${jewelrySmall} 400w, ${jewelryLarge} 800w`, width: 800, height: 800 },
  shoes: { src: shoesLarge, srcSet: `${shoesSmall} 400w, ${shoesLarge} 800w`, width: 800, height: 800 },
  headphones: { src: headphonesLarge, srcSet: `${headphonesSmall} 400w, ${headphonesLarge} 800w`, width: 800, height: 800 },
  livingroom: { src: livingroomLarge, srcSet: `${livingroomSmall} 400w, ${livingroomLarge} 800w`, width: 800, height: 1200 },
  portrait: { src: portraitLarge, srcSet: `${portraitSmall} 400w, ${portraitLarge} 800w`, width: 800, height: 1200 },
  figtart: { src: figtartLarge, srcSet: `${figtartSmall} 400w, ${figtartLarge} 800w`, width: 800, height: 1067 }
};
