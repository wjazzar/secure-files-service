import { ShieldCheckIcon } from 'lucide-react';

import { mockLabels } from '@/i18n/mock-messages';

import type { useSignIn } from '../hooks/use-sign-in';
import { DemoAccounts } from './demo-accounts';
import { LoginForm } from './login-form';

/**
 * Mock mode only: the username / password form, the demo accounts, and the
 * note that goes with them. Loaded on demand outside production builds
 * (login.tsx): a production build contains none of it (audit S-12).
 */
export default function MockSignIn({ signIn }: { signIn: ReturnType<typeof useSignIn> }) {
  return (
    <>
      <LoginForm signIn={signIn} />
      <DemoAccounts signIn={signIn} />
      <div className="login-card-security">
        <ShieldCheckIcon aria-hidden="true" />
        <p>{mockLabels.login.securityNote}</p>
      </div>
    </>
  );
}
