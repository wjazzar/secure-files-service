import { z } from 'zod';

import { mockLabels } from '@/i18n/mock-messages';

/** Login form validation. The identity provider remains the only judge of the credentials. */
const LoginSchema = z.object({
  username: z.string().trim().min(1, { error: mockLabels.login.usernameRequired }).max(100),
  password: z.string().min(1, { error: mockLabels.login.passwordRequired }).max(256),
});
export type LoginValues = z.infer<typeof LoginSchema>;
export type LoginFieldErrors = Partial<Record<keyof LoginValues, string>>;

export function validateLogin(values: LoginValues): { data: LoginValues } | { errors: LoginFieldErrors } {
  const result = LoginSchema.safeParse(values);
  if (result.success) return { data: result.data };
  const errors: LoginFieldErrors = {};
  for (const issue of result.error.issues) {
    const field = issue.path[0];
    if ((field === 'username' || field === 'password') && !errors[field]) errors[field] = issue.message;
  }
  return { errors };
}
