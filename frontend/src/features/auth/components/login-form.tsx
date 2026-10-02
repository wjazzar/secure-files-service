import { ArrowRightIcon, EyeIcon, EyeOffIcon, LoaderIcon, LockIcon, TriangleAlertIcon, UserIcon } from 'lucide-react';
import { type KeyboardEvent, type SubmitEvent, useId, useRef, useState } from 'react';

import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { mockLabels } from '@/i18n/mock-messages';
import { LoginError } from '@/lib/auth/auth-adapter';

import type { useSignIn } from '../hooks/use-sign-in';
import { type LoginFieldErrors, validateLogin } from '../lib/login-schema';

function errorMessage(error: unknown): string {
  if (error instanceof LoginError) {
    if (error.code === 'TOO_MANY_ATTEMPTS' && error.retryAfterSeconds) {
      return mockLabels.login.tooManyAttemptsIn(error.retryAfterSeconds);
    }
    return mockLabels.login.errors[error.code];
  }
  return mockLabels.login.errors.UNAVAILABLE;
}

interface LoginFormProps {
  signIn: ReturnType<typeof useSignIn>;
}

/**
 * Username / password form. Accessible: labelled fields, errors linked to
 * their field and announced, focus moved to the first invalid field, browser
 * password managers supported (`autocomplete`).
 */
export function LoginForm({ signIn }: LoginFormProps) {
  const ids = { username: useId(), password: useId(), usernameError: useId(), passwordError: useId() };
  const usernameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<LoginFieldErrors>({});
  const [showPassword, setShowPassword] = useState(false);
  const [capsLock, setCapsLock] = useState(false);

  const onSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    const result = validateLogin({ username, password });
    if ('errors' in result) {
      setFieldErrors(result.errors);
      (result.errors.username ? usernameRef : passwordRef).current?.focus();
      return;
    }
    setFieldErrors({});
    signIn.mutate(result.data, {
      onError: () => {
        setPassword('');
        passwordRef.current?.focus();
      },
    });
  };

  const detectCapsLock = (event: KeyboardEvent<HTMLInputElement>) => setCapsLock(event.getModifierState('CapsLock'));
  const pending = signIn.isPending;

  return (
    <form noValidate onSubmit={onSubmit} className="login-form space-y-5" aria-busy={pending}>
      {signIn.isError && (
        <Alert variant="destructive" role="alert">
          <TriangleAlertIcon />
          <AlertDescription>{errorMessage(signIn.error)}</AlertDescription>
        </Alert>
      )}

      <div className="space-y-1.5">
        <label htmlFor={ids.username} className="login-label">
          {mockLabels.login.username}
        </label>
        <div className="relative">
          <UserIcon
            aria-hidden
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            ref={usernameRef}
            id={ids.username}
            name="username"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            // Signing in is the only task of this page: the first field gets the focus.
            // eslint-disable-next-line jsx-a11y/no-autofocus
            autoFocus
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            placeholder={mockLabels.login.usernamePlaceholder}
            aria-invalid={fieldErrors.username ? true : undefined}
            aria-describedby={fieldErrors.username ? ids.usernameError : undefined}
            className="login-field h-12 pl-10"
          />
        </div>
        {fieldErrors.username && (
          <p id={ids.usernameError} className="text-sm text-destructive">
            {fieldErrors.username}
          </p>
        )}
      </div>

      <div className="space-y-1.5">
        <label htmlFor={ids.password} className="login-label">
          {mockLabels.login.password}
        </label>
        <div className="relative">
          <LockIcon
            aria-hidden
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            ref={passwordRef}
            id={ids.password}
            name="password"
            type={showPassword ? 'text' : 'password'}
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            onKeyUp={detectCapsLock}
            onKeyDown={detectCapsLock}
            aria-invalid={fieldErrors.password ? true : undefined}
            aria-describedby={fieldErrors.password ? ids.passwordError : undefined}
            className="login-field h-12 pr-12 pl-10"
          />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="absolute top-1/2 right-1.5 -translate-y-1/2"
            onClick={() => setShowPassword((shown) => !shown)}
            aria-label={showPassword ? mockLabels.login.hidePassword : mockLabels.login.showPassword}
            aria-pressed={showPassword}
          >
            {showPassword ? <EyeOffIcon /> : <EyeIcon />}
          </Button>
        </div>
        {fieldErrors.password && (
          <p id={ids.passwordError} className="text-sm text-destructive">
            {fieldErrors.password}
          </p>
        )}
        {capsLock && (
          <p className="flex items-center gap-1.5 text-xs text-status-warning">
            <TriangleAlertIcon aria-hidden className="size-3.5" />
            {mockLabels.login.capsLock}
          </p>
        )}
      </div>

      <Button type="submit" size="lg" className="login-primary-action" disabled={pending}>
        {pending && <LoaderIcon data-icon="inline-start" className="animate-spin" />}
        {pending ? mockLabels.login.submitting : mockLabels.login.submit}
        {!pending && <ArrowRightIcon data-icon="inline-end" />}
      </Button>
    </form>
  );
}
