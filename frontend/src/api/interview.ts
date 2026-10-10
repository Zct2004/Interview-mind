export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface ResumeItem {
  resumeId: number
  fileId: number
  originalName: string
  fileSize: number
  contentType: string
  status: string
  createdAt: string
}

export interface CurrentQuestion {
  sessionId: number
  questionNo: number
  totalQuestions: number
  question: string
  type: string
  difficulty: string
  knowledgePoint: string
}

export interface StartInterviewResponse {
  sessionId: number
  status: string
  totalQuestions: number
  firstQuestion: CurrentQuestion
}

export interface SubmitAnswerResponse {
  sessionId: number
  interviewFinished: boolean
  nextQuestion: CurrentQuestion | null
}

export interface QuestionEvaluation {
  questionNo: number
  question: string
  userAnswer: string
  referenceAnswer: string | null
  score: number
  analysis: string
  weakPoints: string[]
  suggestions: string[]
}

export interface RecordingUploadResponse {
  objectKey: string
  originalName: string
  fileSize: number
  contentType: string | null
  fileId: number | null
  duplicate: boolean
  createdAt: string
}

export interface RecordingReviewQuestion {
  id: number
  questionNo: number
  question: string | null
  questionType: string | null
  knowledgePoints: string
  interviewerFollowUp: string | null
  candidateAnswer: string | null
  answerSummary: string | null
  answerStrengths: string
  missingPoints: string
  referenceAnswer: string | null
  improvedAnswer: string | null
  evidence: string | null
  confidence: string | null
}

export interface RecordingReview {
  id: number
  objectKey: string
  resumeId: number | null
  summary: string | null
  strengths: string
  weakPoints: string
  suggestions: string
  keyTopics: string
  processingStatus: string
  vectorStatus: string
  createdAt: string
  updatedAt: string
}

export interface RecordingReviewDetail {
  review: RecordingReview
  questions: RecordingReviewQuestion[]
}

export interface RecordingLibraryItem {
  fileId: number | null
  objectKey: string
  originalName: string
  fileSize: number | null
  contentType: string | null
  uploadedAt: string | null
  review: RecordingReview | null
}

export interface InterviewReport {
  sessionId: number
  totalScore: number
  summary: string
  strengths: string[]
  weakPoints: string[]
  suggestions: string[]
  finishedAt: string
  questionEvaluations: QuestionEvaluation[]
}

export interface FinishInterviewResponse {
  sessionId: number
  status: string
  report: InterviewReport
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        ...(init.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
        ...init.headers,
      },
    })
  } catch {
    throw new Error('无法连接后端服务。请确认 Spring Boot 已启动（默认地址 localhost:8080）。')
  }

  const contentType = response.headers.get('content-type') || ''
  const body = contentType.includes('application/json') ? await response.json() : null
  if (!response.ok) {
    throw new Error(body?.message || `请求失败（HTTP ${response.status}）`)
  }
  if (body && typeof body.code === 'number') {
    if (body.code !== 200) throw new Error(body.message || '请求失败')
    return body.data as T
  }
  if (body === null) throw new Error('后端返回了无法识别的响应格式')
  return body as T
}

export const interviewApi = {
  listResumes: () => request<ResumeItem[]>('/api/resumes'),

  deleteResume(resumeId: number) {
    return request<void>(`/api/resumes/${resumeId}`, { method: 'DELETE' })
  },

  uploadResume(file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<ResumeItem>('/api/resumes', { method: 'POST', body: form })
  },

  parseResume(resumeId: number) {
    return request<{ resumeId: number; profileId: number; status: string }>(
      `/api/resumes/${resumeId}/parse`,
      { method: 'POST' },
    )
  },

  uploadRecording(file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<RecordingUploadResponse>('/api/recording-reviews/upload', {
      method: 'POST',
      body: form,
    })
  },

  analyzeRecording(objectKey: string) {
    const params = new URLSearchParams({ objectKey })
    return request<RecordingReview>(`/api/recording-reviews/analyze?${params.toString()}`, {
      method: 'POST',
    })
  },

  getRecordingReview(reviewId: number) {
    return request<RecordingReviewDetail>(`/api/recording-reviews/${reviewId}`)
  },

  listRecordingReviews() {
    return request<RecordingLibraryItem[]>('/api/recording-reviews')
  },

  indexRecordingReview(reviewId: number, resumeId: number) {
    const params = new URLSearchParams({ resumeId: String(resumeId) })
    return request<RecordingReview>(`/api/recording-reviews/${reviewId}/index?${params.toString()}`, {
      method: 'POST',
    })
  },

  indexAllRecordingReviews(resumeId: number) {
    const params = new URLSearchParams({ resumeId: String(resumeId) })
    return request<{ resumeId: number; indexed: number; alreadyIndexed: number; skippedOtherResume: number; failed: number; noReport: number }>(
      `/api/recording-reviews/index-all?${params.toString()}`,
      { method: 'POST' },
    )
  },

  start(resumeId: number) {
    return request<StartInterviewResponse>('/api/interviews', {
      method: 'POST',
      body: JSON.stringify({ resumeId }),
    })
  },

  currentQuestion(sessionId: number) {
    return request<CurrentQuestion>(`/api/interviews/${sessionId}/current-question`)
  },

  submitAnswer(sessionId: number, answer: string) {
    return request<SubmitAnswerResponse>(`/api/interviews/${sessionId}/answers`, {
      method: 'POST',
      body: JSON.stringify({ answer }),
    })
  },

  finish(sessionId: number) {
    return request<FinishInterviewResponse>(`/api/interviews/${sessionId}/finish`, {
      method: 'POST',
    })
  },
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function formatDate(value: string): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}