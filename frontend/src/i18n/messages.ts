import { ApiError, type ErrorReason } from '@/api/api-error';
import type { FileStatus, StatusReasonCode } from '@/api/files/files-schemas';
import { env } from '@/config/env';
import { formatBytes } from '@/lib/format';

/**
 * Every user-visible string of the application (rule F-11).
 *
 * Tables are typed `Record<Code, string>`: a code added to the contract (and
 * to the Zod schemas) without a message does not compile.
 */

const maxSize = formatBytes(env.VITE_MAX_UPLOAD_BYTES);

export const errorMessages: Record<ErrorReason, string> = {
  INVALID_PARAMETER: 'La requête contient un paramètre invalide.',
  INVALID_FILE_NAME: 'Le nom de ce fichier n’est pas accepté.',
  EMPTY_FILE: 'Ce fichier est vide.',
  LENGTH_REQUIRED: 'La taille du fichier n’a pas été transmise.',
  CONTENT_LENGTH_MISMATCH: 'Le fichier reçu est incomplet. Réessayez.',
  FILE_TOO_LARGE: `Fichier trop volumineux (maximum : ${maxSize}).`,
  UPLOAD_TOO_SLOW: 'L’envoi était trop lent et a été interrompu. Vérifiez votre connexion, puis réessayez.',
  IDEMPOTENCY_REQUEST_IN_PROGRESS: 'Cet envoi est déjà en cours de traitement.',
  IDEMPOTENCY_KEY_REUSED: 'Conflit d’envoi : déposez à nouveau le fichier.',
  TOO_MANY_PENDING_FILES: 'Le service est très sollicité. Réessayez dans quelques instants.',
  TOO_MANY_CONCURRENT_UPLOADS: 'Le service reçoit beaucoup de fichiers en ce moment. Réessayez dans un instant.',
  UNAUTHENTICATED: 'Votre session a expiré. Reconnectez-vous.',
  CSRF_TOKEN_INVALID: 'Votre session doit être rechargée : actualisez la page, puis réessayez.',
  FILE_NOT_FOUND: 'Ce fichier est introuvable.',
  FILE_NOT_READY: 'L’analyse de ce fichier n’est pas encore terminée.',
  FILE_INFECTED: 'Téléchargement impossible : une menace a été détectée.',
  FILE_UNSCANNABLE: 'Téléchargement impossible : le fichier n’a pas pu être analysé.',
  FILE_SCAN_FAILED: 'Téléchargement impossible : l’analyse a échoué.',
  RANGE_NOT_SATISFIABLE: 'La reprise du téléchargement a échoué. Relancez-le.',
  SERVICE_UNAVAILABLE: 'Service momentanément indisponible. Réessayez dans quelques instants.',
  INTERNAL_ERROR: 'Une erreur inattendue est survenue.',
  UNKNOWN: 'Une erreur inattendue est survenue.',
  NETWORK_ERROR: 'Connexion au service perdue. Vérifiez votre réseau, puis réessayez.',
  TIMEOUT: 'Le service met trop de temps à répondre. Réessayez.',
  INVALID_RESPONSE: 'Réponse inattendue du service.',
  CANCELED: 'Opération annulée.',
};

/** Message for any error thrown by an API call. */
export function describeError(error: unknown): string {
  return errorMessages[error instanceof ApiError ? error.reason : 'UNKNOWN'];
}

export const statusLabels: Record<FileStatus, string> = {
  PENDING: 'En attente d’analyse',
  SCANNING: 'Analyse en cours',
  AVAILABLE: 'Disponible',
  INFECTED: 'Menace détectée',
  UNSCANNABLE: 'Non analysable',
  FAILED: 'Échec de l’analyse',
  UNKNOWN: 'État inconnu',
};

export const statusReasonMessages: Record<StatusReasonCode, string> = {
  SCAN_RETRY_SCHEDULED: 'L’analyse a rencontré un incident. Un nouvel essai est programmé automatiquement.',
  EXCEEDS_SCANNER_SIZE_LIMIT: 'Le fichier dépasse la taille que l’antivirus peut analyser.',
  ENCRYPTED_ARCHIVE: 'L’archive est chiffrée ou protégée par mot de passe : son contenu ne peut pas être inspecté.',
  SCANNER_LIMITS_EXCEEDED:
    'Le fichier dépasse les limites d’analyse de l’antivirus (profondeur d’archive, nombre de fichiers…).',
  SCAN_ATTEMPTS_EXHAUSTED: 'L’analyse a échoué après plusieurs tentatives.',
  UNKNOWN: 'Le service n’a pas précisé la raison.',
};

export const labels = {
  appTitle: 'Fichiers sécurisés',
  appTagline: 'Chaque fichier est analysé par un antivirus avant de pouvoir être téléchargé.',
  appEyebrow: 'Espace de dépôt sécurisé',
  footer:
    'La marque et le logo Praxedo appartiennent à leur propriétaire ; ils ne sont utilisés ici que dans le cadre d’un test technique.',
  footerVersion: 'Accès authentifié · espace privé',

  steps: [
    { title: 'Déposez', text: 'Vos fichiers sont reçus en quarantaine, jamais servis tels quels.' },
    { title: 'Analyse antivirus', text: 'Chaque fichier est inspecté automatiquement, sans action de votre part.' },
    { title: 'Téléchargez', text: 'Seuls les fichiers déclarés sains deviennent téléchargeables.' },
  ],

  pipelineLabel: 'Parcours de chaque fichier',

  stats: {
    label: 'Filtrer par statut',
    total: 'Tous',
    available: 'Disponibles',
    inProgress: 'En cours',
    blocked: 'Bloqués',
  },
  skipToContent: 'Aller au contenu',
  retry: 'Réessayer',
  cancel: 'Annuler',
  dismiss: 'Retirer',
  download: 'Télécharger',
  downloading: 'Préparation…',
  details: 'Détails',
  signOut: 'Se déconnecter',
  signOutFailed: 'La déconnexion n’a pas abouti : vous êtes toujours connecté. Réessayez.',

  theme: {
    label: 'Thème de l’interface',
    light: 'Clair',
    dark: 'Sombre',
  },

  login: {
    title: 'Connexion',
    subtitle: 'Accédez à votre espace de dépôt sécurisé.',
    privateSpace: 'Espace privé',
    accessLabel: 'Votre espace personnel',
    accessCaption: 'Vos fichiers ne sont accessibles qu’à vous.',
    brandEyebrow: 'La confiance, dès le premier fichier',
    brandTitleAccent: 'En lieu sûr.',
    brandDescription: 'Déposez en toute confiance. Chaque fichier est analysé avant de pouvoir être téléchargé.',
    sceneScan: 'Analyse antivirus',
    scenePrivate: 'Accès privé',
    pauseAnimation: 'Mettre l’animation en pause',
    resumeAnimation: 'Reprendre l’animation',
    footerNote: 'La sécurité, par conception',
    sessionExpired: 'Votre session a expiré. Reconnectez-vous pour continuer.',
    signedOut: 'Vous êtes déconnecté.',
    redirectTitle: 'Votre compte d’entreprise',
    redirectHint: 'Un accès unique à votre espace sécurisé',
    redirectNote: 'Vous serez redirigé vers votre page d’authentification.',
    redirectButton: 'Se connecter',
    redirecting: 'Redirection…',
    signInFailed: 'La connexion n’a pas abouti. Réessayez.',
    signInCancelled: 'La connexion a été annulée.',
    brandTitle: 'Vos fichiers.',
    securityNoteSession: 'Mot de passe saisi chez le fournisseur d’identité · Aucun jeton dans le navigateur',
  },

  upload: {
    title: 'Déposer des fichiers',
    dropHint: 'Glissez-déposez vos fichiers ici',
    or: 'ou',
    browse: 'Choisir des fichiers',
    constraints: `Taille maximale : ${maxSize} par fichier.`,
    subtitle: 'Les fichiers sont analysés dès leur réception.',
    pillMaxSize: `${maxSize} maximum`,
    pillScan: 'Analyse automatique',
    pillMultiple: 'Plusieurs fichiers à la fois',
    dropActive: 'Relâchez pour déposer',
    queueTitle: 'Envois en cours',
    clearFinished: 'Effacer les terminés',
    queued: 'En attente',
    uploading: 'Envoi…',
    succeeded: 'Envoyé — analyse en cours',
    canceled: 'Envoi annulé',
  },

  files: {
    title: 'Mes fichiers',
    searchLabel: 'Rechercher par nom',
    searchPlaceholder: 'Rechercher un fichier…',
    clearSearch: 'Effacer la recherche',
    clearFilters: 'Réinitialiser les filtres',
    showAll: 'Voir tous les fichiers',
    columns: {
      filename: 'Nom',
      size: 'Taille',
      status: 'Statut',
      uploadedAt: 'Déposé le',
      actions: 'Actions',
    },
    sortAscending: 'tri croissant',
    sortDescending: 'tri décroissant',
    emptyTitle: 'Aucun fichier pour le moment',
    emptyHint: 'Déposez un premier fichier ci-dessus pour le voir apparaître ici.',
    noMatchTitle: 'Aucun fichier ne correspond',
    noMatchHint: 'Modifiez la recherche ou les filtres.',
    loadError: 'Impossible de charger les fichiers.',
    live: 'Suivi en direct',
    liveHint: 'Des fichiers sont en cours d’analyse : le tableau se met à jour automatiquement.',
    tableCaption: 'Liste de vos fichiers et de leur état d’analyse',
    subtitle: 'Suivi en temps réel de l’analyse antivirus',
  },

  pagination: {
    rowsPerPage: 'Lignes par page',
    range: (from: number, to: number, total: number) => `${from}–${to} sur ${total}`,
    page: (page: number, total: number) => `Page ${page} sur ${Math.max(total, 1)}`,
    first: 'Première page',
    previous: 'Page précédente',
    next: 'Page suivante',
    last: 'Dernière page',
  },

  detail: {
    loading: 'Chargement du fichier…',
    timeline: 'Parcours du fichier',
    stepReceived: 'Reçu en quarantaine',
    stepScanning: 'Analyse antivirus',
    stepScanningNow: 'Analyse en cours…',
    stepWaiting: 'En attente',
    stepAvailable: 'Disponible au téléchargement',
    stepBlocked: 'Bloqué',
    verdictClean: 'Aucune menace détectée',
    verdictPending: 'Analyse en attente',
    copy: 'Copier',
    copied: 'Empreinte SHA-256 copiée',
    analysis: 'Analyse antivirus',
    noVerdictYet: 'Aucun verdict pour le moment : l’analyse n’est pas terminée.',
    noVerdict: 'Aucun verdict n’a pu être obtenu.',
    engine: 'Moteur',
    signatures: 'Base de signatures',
    scannedAt: 'Analysé le',
    duration: 'Durée',
    threat: 'Menace',
    attempts: 'Tentatives',
    information: 'Informations',
    size: 'Taille',
    type: 'Type détecté',
    sha256: 'Empreinte SHA-256',
    uploadedAt: 'Déposé le',
    statusChangedAt: 'Dernier changement',
    blockedTitle: {
      INFECTED: 'Ce fichier est bloqué',
      UNSCANNABLE: 'Ce fichier n’a pas pu être analysé',
      FAILED: 'L’analyse de ce fichier a échoué',
    },
    blockedConsequence: 'Il ne sera jamais proposé au téléchargement.',
    infectedAdvice: 'Supprimez-le de votre poste et prévenez votre service informatique.',
    unscannableAdvice: 'Déposez-le à nouveau sans chiffrement, ou en plusieurs fichiers plus petits.',
    failedAdvice: 'Déposez-le à nouveau. Si le problème persiste, contactez le support.',
  },

  notFound: {
    title: 'Page introuvable',
    back: 'Revenir à mes fichiers',
  },
  crash: {
    title: 'L’application a rencontré un problème',
    reload: 'Recharger la page',
  },
} as const;
