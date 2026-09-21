/** Academic Plugin API v2 (compatible with v1). IDs are opaque and stable across refreshes. */
export type ErrorCode = 'UNSUPPORTED' | 'NOT_OPEN' | 'INVALID_CREDENTIALS' | 'SESSION_EXPIRED' |
  'CAPTCHA_REQUIRED' | 'WEB_LOGIN_REQUIRED' | 'NO_CAPACITY' | 'CONFLICT' | 'ALREADY_SELECTED' |
  'CREDIT_LIMIT' | 'PAGE_CHANGED' | 'NETWORK_RETRYABLE' | 'RESULT_UNKNOWN' | 'UNTRUSTED_URL' |
  'VALIDATION_FAILED' | 'TIMEOUT' | 'CANCELLED' | 'RUNTIME_EXITED' | 'RESOURCE_LIMIT' | 'BAD_SIGNATURE';
export interface PluginError { code: ErrorCode; message: string; details?: Record<string, string>; }
export type Result<T> = { ok: true; data: T } | { ok: false; error: PluginError };
export interface Context {
  schoolId: string; accountId: string; sessionEpoch: number;
  providerId: string; providerVersion: string; operationId: string;
  baseUrl: string; development: boolean;
}
export interface Term {
  id: string; name: string; year?: number; semester?: number; order?: number;
  startDate?: string; endDate?: string; nextId?: string;
}
export interface Terms { items: Term[]; currentId: string; }
export interface Page<T> { items: T[]; nextCursor?: string; }
export interface PageQuery { cursor?: string; pageSize?: number; }
export interface AuthStart { username: string; password: string; }
export interface AuthResume { continuationId: string; captcha?: string; webLoginCompleted?: boolean; }
export type AuthState =
  { status: 'authenticated'; studentId: string; studentName: string } |
  { status: 'captcha'; continuationId: string; imageBase64: string; mimeType: string } |
  { status: 'webLogin'; continuationId: string; url: string; completionUrl: string };
export interface ScheduleEntry {
  id: string; name: string; teacher?: string; location?: string;
  day: number; startPeriod: number; endPeriod: number; weeks: number[];
}
export interface Schedule { termId: string; entries: ScheduleEntry[]; maxWeeks: number; }
export interface Calendar {
  termId: string; startDate?: string;
  periods: { number: number; start: string; end: string }[];
}
export interface Grade {
  id: string; name: string; score: string; credits?: string; gradePoint?: string;
  type?: string; termId?: string; code?: string; college?: string; sectionId?: string;
}
export interface Grades extends Page<Grade> { gradePointAverage?: string; totalCredits?: string; }
export interface GradeDetails { gradeId: string; items: { name: string; score: string; weight?: string }[]; }
export interface Exam { id: string; name: string; time: string; location?: string; seat?: string; examName?: string; teacher?: string; }
export interface Round { id: string; name: string; termId?: string; open: boolean; }
export interface Course {
  id: string; name: string; roundId: string; teacher?: string; time?: string; location?: string;
  credits?: string; capacity?: number; selected?: number; sectionId?: string; sectionName?: string;
}
export interface Section { id: string; courseId: string; name?: string; teacher?: string; time?: string; location?: string; capacity?: number; selected?: number; }
export interface Enrollment { id: string; courseId: string; sectionId: string; name: string; teacher?: string; time?: string; location?: string; credits?: string; }
export interface SelectionTarget { courseId: string; sectionId: string; roundId: string; }
export interface SelectionReceipt { confirmed: boolean; enrollment?: Enrollment; message?: string; }
export interface HttpRequest {
  url: string; method?: 'GET' | 'POST'; purpose: 'query' | 'auth' | 'mutation';
  headers?: Record<string, string>; form?: Record<string, string>; body?: string;
  charset?: 'UTF-8' | 'GBK' | 'GB2312' | 'GB18030'; responseType?: 'text' | 'base64';
}
export interface HttpResponse { status: number; url: string; headers: Record<string, string>; body: string; }
export interface HtmlNode { text: string; html: string; attributes: Record<string, string>; }
export interface HostSdk {
  http(request: HttpRequest): Promise<HttpResponse>;
  html: {
    select(html: string, selector: string): HtmlNode[];
    text(html: string): string;
    form(html: string, selector?: string): Record<string, string>;
    table(html: string, selector?: string): string[][];
  };
  crypto: {
    digest(algorithm: 'SHA-256' | 'MD5', text: string): Promise<string>;
    hmacSha256(key: string, text: string): Promise<string>;
    aesCbcEncrypt(keyBase64: string, ivBase64: string, text: string): Promise<string>;
    rsaEncrypt(publicKeySpkiBase64: string, text: string): Promise<string>;
    base64(text: string): Promise<string>;
  };
  /** Session state may retain protocol tokens; it is discarded on session invalidation. */
  state: { get<T = unknown>(key: string): Promise<T | null>; set(key: string, value: unknown): Promise<void>; remove(key: string): Promise<void> };
  /** Persistent storage is scoped by school/account/provider. Never store credentials here. */
  storage: { get<T = unknown>(key: string): Promise<T | null>; set(key: string, value: unknown): Promise<void>; remove(key: string): Promise<void> };
  log(level: 'debug' | 'info' | 'warn' | 'error', message: string, fields?: Record<string, unknown>): Promise<void>;
}
export type Handler<A, R> = (args: A, context: Readonly<Context>, sdk: HostSdk) => Promise<Result<R>> | Result<R>;
export interface Auth {
  start: Handler<AuthStart, AuthState>;
  resume: Handler<AuthResume, AuthState>;
  refreshCaptcha: Handler<{ continuationId: string }, AuthState>;
  validate: Handler<Record<string, never>, AuthState>;
}
export interface Study {
  terms: Handler<Record<string, never>, Terms>;
  schedule: Handler<{ termId: string }, Schedule>;
  calendar: Handler<{ termId: string }, Calendar>;
  grades: Handler<PageQuery & { termId?: string }, Grades>;
  gradeDetails: Handler<{ gradeId: string }, GradeDetails>;
  exams: Handler<PageQuery & { termId: string }, Page<Exam>>;
}
export interface Selection {
  catalog: Handler<PageQuery, Page<Round>>;
  courses: Handler<PageQuery & { roundId: string; keyword?: string; teacher?: string }, Page<Course>>;
  sections: Handler<PageQuery & { courseId: string; roundId: string }, Page<Section>>;
  enrolled: Handler<PageQuery & { roundId?: string }, Page<Enrollment>>;
  select: Handler<SelectionTarget, SelectionReceipt>;
  drop: Handler<SelectionTarget & { enrollmentId: string }, SelectionReceipt>;
}
/** Authentication and selection are replaced as complete groups. Study methods are independent. */
export interface AcademicPlugin { auth?: Auth; study?: Partial<Study>; selection?: Selection; }
export type Capability = `auth.${keyof Auth}` | `study.${keyof Study}` | `selection.${keyof Selection}` | `service.${keyof Service}`;
export interface NetworkRule {
  origin: string; pathPrefix: string; methods: ('GET' | 'POST')[]; purposes: ('query' | 'auth' | 'mutation')[];
  requiredQuery?: Record<string, string>; requiredForm?: Record<string, string>;
}
export interface PluginManifest {
  id: string; name: string; version: string; apiVersion: 1 | 2;
  kind: 'configuration' | 'extension' | 'independent' | 'service';
  extends?: 'builtin.zf' | 'builtin.zf_old' | 'builtin.qz' | 'builtin.qz_old' | 'builtin.legacy_zf';
  entry?: 'index.js'; capabilities: Capability[]; network: NetworkRule[];
  school: { id: string; name: string; domain: string; protocol: 'http' | 'https'; basePath: string; academicSystem?: string; allowedAcademicHosts?: string[]; pageCharset?: string };
  service?: ServiceManifest;
  description?: string; files: Record<string, string>;
}

/** API v2: separate service sessions and native, declarative pages. API v1 remains supported. */
export interface ServiceManifest {
  schoolIds: string[];
  /** Exact academic host aliases, for custom school configurations with a different ID. */
  academicHosts?: string[];
  authentication: { mode: 'none' | 'password'; usernameLabel?: string; passwordLabel?: string; help?: string };
  pages: { id: string; title: string }[];
  entries: { id: string; title: string; pageId: string; icon: 'school' | 'book' | 'chart' | 'calendar' | 'wallet' | 'activity'; order: number }[];
  actions: { id: string; title: string; kind: 'query' | 'mutation'; confirmation?: string }[];
}
export type ServiceParams = Record<string, string>;
export type ServiceLink = { type: 'page'; pageId: string; params?: ServiceParams } |
  { type: 'action'; actionId: string; params?: ServiceParams } | { type: 'url'; url: string };
export type ServiceTone = 'default' | 'positive' | 'warning';
export interface ServiceField {
  id: string; label: string; type: 'text' | 'number' | 'select'; required: boolean;
  placeholder?: string; value?: string; options?: { value: string; label: string }[];
}
export type ServiceBlock =
  { id: string; type: 'profile'; title: string; subtitle?: string; details?: string[]; badge?: string } |
  { id: string; type: 'metrics'; title?: string; columns: 1 | 2 | 3; items: { id: string; label: string; value: string; unit?: string }[] } |
  { id: string; type: 'progress'; title: string; items: { id: string; label: string; value: number; max: number; detail?: string; displayValue?: string; tone?: ServiceTone }[] } |
  { id: string; type: 'list'; title?: string; items: { id: string; title: string; subtitle?: string; value?: string; action?: ServiceLink }[] } |
  { id: string; type: 'notice'; text: string; tone?: ServiceTone } |
  { id: string; type: 'actions'; title?: string; items: { label: string; action: ServiceLink }[] } |
  { id: string; type: 'form'; title?: string; fields: ServiceField[]; submit: { label: string; actionId: string } };
export interface ServicePage { pageId: string; title: string; subtitle?: string; layout?: 'comfortable' | 'compact'; blocks: ServiceBlock[] }
export interface ServiceReceipt { actionId: string; confirmed: boolean; message?: string; page?: ServicePage }
export interface Service {
  page: Handler<{ pageId: string; params?: ServiceParams }, ServicePage>;
  /** Only declared mutation actions can send purpose=mutation, after the host's confirmation. */
  action: Handler<{ actionId: string; params?: ServiceParams }, ServiceReceipt>;
}
export interface CampusServicePlugin { auth?: Auth; service: Pick<Service, 'page'> & Partial<Pick<Service, 'action'>> }
