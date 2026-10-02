import { FingerprintIcon, ShieldCheckIcon } from 'lucide-react';
import { useId } from 'react';

import { labels } from '@/i18n/messages';

/** Decorative vector artwork: no images, network calls or animation runtime. */
export function SecureVaultScene() {
  const id = useId();
  const halo = `${id}-halo`;
  const face = `${id}-face`;
  const top = `${id}-top`;
  const side = `${id}-side`;
  const edge = `${id}-edge`;

  return (
    <div className="login-vault-scene" aria-hidden="true">
      <svg className="login-vault-art" viewBox="0 0 560 340" fill="none">
        <defs>
          <radialGradient id={halo}>
            <stop stopColor="var(--login-cyan)" stopOpacity=".2" />
            <stop offset=".5" stopColor="var(--login-cyan)" stopOpacity=".07" />
            <stop offset="1" stopColor="var(--login-cyan)" stopOpacity="0" />
          </radialGradient>
          <linearGradient id={face} x1="190" y1="130" x2="280" y2="282" gradientUnits="userSpaceOnUse">
            <stop stopColor="var(--login-cyan)" stopOpacity=".14" />
            <stop offset="1" stopColor="var(--login-cyan)" stopOpacity=".025" />
          </linearGradient>
          <linearGradient id={top} x1="190" y1="130" x2="365" y2="130" gradientUnits="userSpaceOnUse">
            <stop stopColor="var(--login-cyan)" stopOpacity=".08" />
            <stop offset="1" stopColor="var(--login-ice)" stopOpacity=".26" />
          </linearGradient>
          <linearGradient id={side} x1="365" y1="132" x2="280" y2="282" gradientUnits="userSpaceOnUse">
            <stop stopColor="var(--login-ice)" stopOpacity=".18" />
            <stop offset="1" stopColor="var(--login-cyan)" stopOpacity=".05" />
          </linearGradient>
          <linearGradient id={edge} x1="190" y1="125" x2="365" y2="260" gradientUnits="userSpaceOnUse">
            <stop stopColor="var(--login-cyan)" stopOpacity=".35" />
            <stop offset=".5" stopColor="var(--login-ice)" />
            <stop offset="1" stopColor="var(--login-cyan)" stopOpacity=".35" />
          </linearGradient>
        </defs>

        <ellipse cx="280" cy="182" rx="250" ry="166" fill={`url(#${halo})`} />
        <g className="login-vault-orbits" stroke="var(--login-cyan)">
          <ellipse cx="280" cy="182" rx="206" ry="111" strokeOpacity=".15" transform="rotate(-24 280 182)" />
          <ellipse
            cx="280"
            cy="182"
            rx="230"
            ry="130"
            strokeOpacity=".2"
            strokeDasharray="2 10"
            transform="rotate(-24 280 182)"
          />
          <ellipse cx="280" cy="182" rx="173" ry="152" strokeOpacity=".09" transform="rotate(32 280 182)" />
          <path d="M86 181C91 228 168 284 262 283" strokeOpacity=".6" />
          <circle cx="86" cy="181" r="3" fill="var(--login-ice)" stroke="none" />
          <circle cx="470" cy="169" r="3" fill="var(--login-cyan)" stroke="none" />
        </g>

        <g stroke="var(--login-cyan)">
          <ellipse cx="280" cy="303" rx="118" ry="21" fill={`url(#${halo})`} strokeOpacity=".2" />
          <ellipse cx="280" cy="303" rx="85" ry="14" strokeOpacity=".3" />
          <path d="M280 16V50M280 314V332M37 182H60M500 182H523" strokeOpacity=".28" />
          <path d="M272 32H288M45 174V190M515 174V190" strokeOpacity=".2" />
        </g>

        <g className="login-vault-cube">
          <path d="M280 76L369 128L280 180L191 128Z" fill={`url(#${top})`} />
          <path d="M191 128L280 180V282L191 230Z" fill={`url(#${face})`} />
          <path d="M280 180L369 128V230L280 282Z" fill={`url(#${side})`} />
          <path d="M280 76L369 128V230L280 282L191 230V128Z" stroke={`url(#${edge})`} strokeWidth="1.3" />
          <path d="M191 128L280 180L369 128M280 180V282" stroke={`url(#${edge})`} strokeWidth="1.3" />
          <path
            d="M280 76V178M191 230L280 178L369 230"
            stroke="var(--login-cyan)"
            strokeOpacity=".18"
            strokeDasharray="3 5"
          />
          <path d="M205 148L264 182V253L205 219Z" stroke="var(--login-cyan)" strokeOpacity=".2" />
          <path d="M296 184L353 151V221L296 254Z" stroke="var(--login-ice)" strokeOpacity=".22" />
          <g transform="translate(325 202) skewY(-30)">
            <path
              d="M0-32L25-22V-2C25 15 15 27 0 33C-15 27-25 15-25-2V-22Z"
              fill="var(--login-cyan)"
              fillOpacity=".1"
              stroke="var(--login-ice)"
              strokeWidth="2"
            />
            <path
              d="M-10 0L-2 8L13-9"
              stroke="var(--login-ice)"
              strokeWidth="3"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </g>
          <g stroke="var(--login-cyan)" strokeOpacity=".4" strokeLinecap="round">
            <path d="M218 180L250 198M218 192L250 210M218 204L238 216" />
          </g>
          <path
            d="M280 66L379 123M379 123V158M181 224V236L207 251M280 292L303 279"
            stroke="var(--login-ice)"
            strokeOpacity=".7"
            strokeWidth="2"
            strokeLinecap="round"
          />
          <circle cx="280" cy="180" r="3" fill="var(--login-ice)" />
          <circle cx="280" cy="76" r="2" fill="var(--login-ice)" />
          <circle cx="369" cy="230" r="2" fill="var(--login-ice)" />
        </g>
      </svg>

      <div className="login-scene-tag login-scene-tag-scan">
        <ShieldCheckIcon />
        <span>{labels.login.sceneScan}</span>
        <span className="login-scene-dot" />
      </div>
      <div className="login-scene-tag login-scene-tag-private">
        <FingerprintIcon />
        <span>{labels.login.scenePrivate}</span>
      </div>
    </div>
  );
}
