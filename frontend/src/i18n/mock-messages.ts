/**
 * Labels of the mock mode only — the sign-in form, the demo accounts, the
 * mock API badge. Kept apart from `messages.ts` so that a production build,
 * which has no mock mode, does not carry them (audit S-12): only modules
 * loaded outside production import this one.
 */
export const mockLabels = {
  mockBadge: 'API simulée',
  mockBadgeHint: 'Données de démonstration servies par le navigateur (MSW)',

  login: {
    username: 'Identifiant',
    usernamePlaceholder: 'ex. alice',
    password: 'Mot de passe',
    showPassword: 'Afficher le mot de passe',
    hidePassword: 'Masquer le mot de passe',
    capsLock: 'Verrouillage des majuscules activé',
    submit: 'Se connecter',
    submitting: 'Connexion…',
    usernameRequired: 'Saisissez votre identifiant.',
    passwordRequired: 'Saisissez votre mot de passe.',
    errors: {
      INVALID_CREDENTIALS: 'Identifiant ou mot de passe incorrect.',
      TOO_MANY_ATTEMPTS: 'Trop de tentatives. Réessayez dans quelques instants.',
      UNAVAILABLE: 'Le service de connexion ne répond pas. Réessayez dans quelques instants.',
    },
    tooManyAttemptsIn: (seconds: number) => `Trop de tentatives. Réessayez dans ${seconds} s.`,
    demoTitle: 'Comptes de démonstration',
    demoHint: 'Choisissez un profil pour explorer la démo. Mot de passe : demo.',
    demoSignIn: (name: string) => `Se connecter en tant que ${name}`,
    securityNote: 'Espace de démonstration · Session conservée en mémoire uniquement',
  },
};
