import {
  ArrowRightIcon,
  FingerprintIcon,
  InfoIcon,
  KeyRoundIcon,
  LoaderIcon,
  LockKeyholeIcon,
  PauseIcon,
  PlayIcon,
  ShieldCheckIcon,
  TriangleAlertIcon,
} from 'lucide-react';
import { lazy, Suspense, useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router';

import { PraxedoLogo } from '@/components/brand/praxedo-logo';
import { ThemeToggle } from '@/components/theme/theme-toggle';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { SecureVaultScene } from '@/features/auth/components/secure-vault-scene';
import { useSignIn } from '@/features/auth/hooks/use-sign-in';
import { labels } from '@/i18n/messages';
import { useAuth } from '@/lib/auth/use-auth';
import { safeRedirect } from '@/lib/safe-redirect';

import '@/features/auth/styles/login.css';

/**
 * The mock sign-in exists outside production builds only: there, the
 * condition is a constant false, and neither the form nor the demo accounts
 * reach the bundle (audit S-12).
 */
const MockSignIn = import.meta.env.DEV ? lazy(() => import('@/features/auth/components/mock-sign-in')) : null;

/** Why the service sent the browser back here instead of into the application (`?error=`). */
function signInError(code: string | null): string | null {
  if (code === 'sign-in-cancelled') return labels.login.signInCancelled;
  return code ? labels.login.signInFailed : null;
}

/** The same visual shell surrounds the mock form and the real identity-provider redirect. */
export function LoginRoute() {
  const { status, loginMode, reason, login } = useAuth();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [redirecting, setRedirecting] = useState(false);
  const [motionPaused, setMotionPaused] = useState(false);
  const redirectTo = safeRedirect(searchParams.get('redirect'));
  const signIn = useSignIn({ onSuccess: () => void navigate(redirectTo, { replace: true }) });

  if (status === 'authenticated') return <Navigate to={redirectTo} replace />;

  // `?signed-out` survives the full-page round trip to Keycloak's end-session page.
  const signedOut = reason === 'signed-out' || searchParams.has('signed-out');
  const notice = reason === 'expired' ? labels.login.sessionExpired : signedOut ? labels.login.signedOut : null;
  const failure = signInError(searchParams.get('error'));
  const redirect = () => {
    setRedirecting(true);
    void login(undefined, redirectTo);
  };

  return (
    <div className="login-screen" data-motion={motionPaused ? 'paused' : 'running'}>
      <div className="login-atmosphere" aria-hidden="true" />

      <header className="login-header">
        <div className="login-brand">
          <PraxedoLogo className="login-logo" />
          <span className="login-brand-divider" aria-hidden="true" />
          <span className="login-wordmark">{labels.appTitle}</span>
        </div>
        <div className="login-header-actions">
          <span className="login-private-badge">
            <LockKeyholeIcon aria-hidden="true" />
            {labels.login.privateSpace}
          </span>
          <ThemeToggle />
        </div>
      </header>

      <main className="login-main">
        <section className="login-hero" aria-labelledby="login-hero-title">
          <p className="login-eyebrow">{labels.login.brandEyebrow}</p>
          {/* Not a heading: the page has one, "Connexion", and nothing may precede it in the outline. */}
          <p id="login-hero-title" className="login-hero-title">
            {labels.login.brandTitle}
            <span className="login-hero-accent">{labels.login.brandTitleAccent}</span>
          </p>
          <p className="login-hero-description">{labels.login.brandDescription}</p>

          <div className="login-scene-wrap">
            <SecureVaultScene />
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              className="login-motion-toggle"
              aria-label={motionPaused ? labels.login.resumeAnimation : labels.login.pauseAnimation}
              aria-pressed={motionPaused}
              onClick={() => setMotionPaused((paused) => !paused)}
            >
              {motionPaused ? <PlayIcon aria-hidden="true" /> : <PauseIcon aria-hidden="true" />}
            </Button>
          </div>

          <ol className="login-journey" aria-label={labels.pipelineLabel}>
            {labels.steps.map((step, index) => (
              <li key={step.title}>
                <span className="login-step-number" aria-hidden="true">{`0${index + 1}`}</span>
                <span>{step.title}</span>
                {index < labels.steps.length - 1 && <ArrowRightIcon aria-hidden="true" />}
              </li>
            ))}
          </ol>
        </section>

        <section className="login-access" aria-labelledby="login-title">
          <div className="login-card">
            <div className="login-card-topline">
              <span className="login-access-icon">
                <FingerprintIcon aria-hidden="true" />
              </span>
              <span className="login-access-label">{labels.login.accessLabel}</span>
              <span className="login-card-corner" aria-hidden="true" />
            </div>

            <div className="login-card-heading">
              <h1 id="login-title">{labels.login.title}</h1>
              <p>{labels.login.subtitle}</p>
            </div>

            {failure && (
              <Alert variant="destructive" role="alert" className="login-notice">
                <TriangleAlertIcon />
                <AlertDescription>{failure}</AlertDescription>
              </Alert>
            )}

            {notice && !failure && (
              <Alert role="status" className="login-notice">
                <InfoIcon />
                <AlertDescription>{notice}</AlertDescription>
              </Alert>
            )}

            {loginMode === 'form' && MockSignIn ? (
              <Suspense fallback={null}>
                <MockSignIn signIn={signIn} />
              </Suspense>
            ) : (
              <div className="login-sso">
                <div className="login-identity">
                  <span className="login-identity-icon">
                    <KeyRoundIcon aria-hidden="true" />
                  </span>
                  <div>
                    <p className="login-identity-title">{labels.login.redirectTitle}</p>
                    <span className="login-identity-hint">{labels.login.redirectHint}</span>
                  </div>
                  <LockKeyholeIcon className="login-identity-lock" aria-hidden="true" />
                </div>
                <Button size="lg" className="login-primary-action" onClick={redirect} disabled={redirecting}>
                  {redirecting && <LoaderIcon data-icon="inline-start" className="animate-spin" />}
                  {redirecting ? labels.login.redirecting : labels.login.redirectButton}
                  {!redirecting && <ArrowRightIcon data-icon="inline-end" aria-hidden="true" />}
                </Button>
                <p className="login-redirect-note">{labels.login.redirectNote}</p>
              </div>
            )}

            {loginMode === 'redirect' && (
              <div className="login-card-security">
                <ShieldCheckIcon aria-hidden="true" />
                <p>{labels.login.securityNoteSession}</p>
              </div>
            )}
          </div>

          <p className="login-access-caption">
            <LockKeyholeIcon aria-hidden="true" />
            {labels.login.accessCaption}
          </p>
        </section>
      </main>

      <footer className="login-footer">
        <p className="login-footer-legal">{labels.footer}</p>
        <span className="login-footer-note">
          <ShieldCheckIcon aria-hidden="true" />
          {labels.login.footerNote}
        </span>
      </footer>
    </div>
  );
}
