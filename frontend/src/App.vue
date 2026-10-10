<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  formatDate,
  formatFileSize,
  interviewApi,
  type CurrentQuestion,
  type InterviewReport,
  type RecordingReview,
  type RecordingReviewDetail,
  type RecordingLibraryItem,
  type RecordingUploadResponse,
  type ResumeItem,
} from './api/interview'

type Phase = 'setup' | 'interview' | 'report' | 'recording-review'

const PARSED_RESUMES_KEY = 'interviewmind.parsedResumeIds'
const ACTIVE_SESSION_KEY = 'interviewmind.activeSession'

function readStoredIds(key: string): number[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? '[]')
    return Array.isArray(value) ? value.filter((item): item is number => Number.isInteger(item)) : []
  } catch {
    return []
  }
}

interface StoredSession {
  sessionId: number
  resumeId: number
  answersCount: number
  allAnswersSubmitted: boolean
}

const phase = ref<Phase>('setup')
const resumes = ref<ResumeItem[]>([])
const selectedResumeId = ref<number | null>(null)
const parsedResumeIds = ref<number[]>(readStoredIds(PARSED_RESUMES_KEY))
const uploadInput = ref<HTMLInputElement | null>(null)
const isLoadingResumes = ref(false)
const isDeletingResume = ref(false)
const isUploading = ref(false)
const isParsing = ref(false)
const isStarting = ref(false)
const isSubmitting = ref(false)
const isFinishing = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const sessionId = ref<number | null>(null)
const currentQuestion = ref<CurrentQuestion | null>(null)
const answer = ref('')
const report = ref<InterviewReport | null>(null)
const answersCount = ref(0)
const activeNav = ref('模拟面试')
const recordingInput = ref<HTMLInputElement | null>(null)
const isDraggingRecording = ref(false)
const isUploadingRecording = ref(false)
const isAnalyzingRecording = ref(false)
const analyzingObjectKey = ref<string | null>(null)
const isIndexingRecording = ref(false)
const isIndexingAllRecordings = ref(false)
const selectedRecordingFile = ref<File | null>(null)
const uploadedRecording = ref<RecordingUploadResponse | null>(null)
const recordingReviewDetail = ref<RecordingReviewDetail | null>(null)
const recordingResultSection = ref<HTMLElement | null>(null)
const recordingLibrary = ref<RecordingLibraryItem[]>([])
const isLoadingRecordingReviews = ref(false)
const allowedAudioExtensions = ['.m4a', '.mp3', '.wav', '.aac', '.flac', '.ogg', '.opus', '.webm', '.mp4']
const maxAudioFileSize = 100 * 1024 * 1024

const selectedResume = computed(
  () => resumes.value.find((resume) => resume.resumeId === selectedResumeId.value) ?? null,
)
const progress = computed(() => {
  if (!currentQuestion.value) return 0
  return Math.round(((currentQuestion.value.questionNo - 1) / currentQuestion.value.totalQuestions) * 100)
})
const isResumeParsed = (resume: ResumeItem) =>
  resume.status === 'PARSED' || parsedResumeIds.value.includes(resume.resumeId)
const isSelectedParsed = computed(
  () => selectedResume.value !== null && isResumeParsed(selectedResume.value),
)

function clearMessages() {
  errorMessage.value = ''
  successMessage.value = ''
}

function showError(error: unknown) {
  errorMessage.value = error instanceof Error ? error.message : '操作失败，请稍后重试。'
}

async function loadResumes() {
  isLoadingResumes.value = true
  try {
    resumes.value = await interviewApi.listResumes()
    if (!resumes.value.some((resume) => resume.resumeId === selectedResumeId.value)) {
      selectedResumeId.value = resumes.value[0]?.resumeId ?? null
    }
  } catch (error) {
    showError(error)
  } finally {
    isLoadingResumes.value = false
  }
}

async function uploadResume(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  clearMessages()
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    errorMessage.value = '目前只支持 PDF 格式的简历。'
    return
  }
  isUploading.value = true
  try {
    const uploaded = await interviewApi.uploadResume(file)
    selectedResumeId.value = uploaded.resumeId
    parsedResumeIds.value = parsedResumeIds.value.filter((id) => id !== uploaded.resumeId)
    localStorage.setItem(PARSED_RESUMES_KEY, JSON.stringify(parsedResumeIds.value))
    successMessage.value = '简历上传成功。下一步请解析简历，生成个人经历画像。'
    await loadResumes()
    selectedResumeId.value = uploaded.resumeId
  } catch (error) {
    showError(error)
  } finally {
    isUploading.value = false
  }
}

function openRecordingReview() {
  activeNav.value = '录音复盘'
  phase.value = 'recording-review'
  clearMessages()
  void loadRecordingReviews()
}

async function loadRecordingReviews() {
  isLoadingRecordingReviews.value = true
  try {
    recordingLibrary.value = await interviewApi.listRecordingReviews()
  } catch (error) {
    showError(error)
  } finally {
    isLoadingRecordingReviews.value = false
  }
}

async function openSavedRecordingReview(reviewId: number) {
  clearMessages()
  try {
    recordingReviewDetail.value = await interviewApi.getRecordingReview(reviewId)
    selectedRecordingFile.value = null
    uploadedRecording.value = null
    await nextTick()
    recordingResultSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch (error) {
    showError(error)
  }
}

async function analyzeSavedRecording(objectKey: string) {
  if (isAnalyzingRecording.value) return
  clearMessages()
  isAnalyzingRecording.value = true
  analyzingObjectKey.value = objectKey
  try {
    const review = await interviewApi.analyzeRecording(objectKey)
    recordingReviewDetail.value = await interviewApi.getRecordingReview(review.id)
    selectedRecordingFile.value = null
    uploadedRecording.value = null
    if (
      selectedResumeId.value !== null
      && (recordingReviewDetail.value.review.resumeId === null || recordingReviewDetail.value.review.resumeId === selectedResumeId.value)
      && recordingReviewDetail.value.review.vectorStatus !== 'INDEXED'
    ) {
      try {
        const indexed = await interviewApi.indexRecordingReview(review.id, selectedResumeId.value)
        recordingReviewDetail.value = { ...recordingReviewDetail.value, review: indexed }
        successMessage.value = '复盘报告已保存，并已自动沉淀到当前简历的个人 RAG 经验库。'
      } catch {
        successMessage.value = '复盘报告已保存，但加入 RAG 失败。可在报告页面重试，或使用“同步全部录音经验”。'
      }
    } else {
      successMessage.value = '复盘报告已保存。选择简历后，可将所有已完成的录音经验统一同步到 RAG。'
    }
    await loadRecordingReviews()
    await nextTick()
    recordingResultSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch (error) {
    showError(error)
  } finally {
    isAnalyzingRecording.value = false
    analyzingObjectKey.value = null
  }
}

function selectRecordingFile(file: File | undefined) {
  if (!file) return
  clearMessages()
  const lowerName = file.name.toLowerCase()
  if (!allowedAudioExtensions.some((extension) => lowerName.endsWith(extension))) {
    errorMessage.value = '暂不支持该音频格式，请选择 M4A、MP3、WAV、AAC、FLAC、OGG、OPUS、WEBM 或 MP4。'
    return
  }
  if (file.size === 0) {
    errorMessage.value = '文件为空，请重新选择录音。'
    return
  }
  if (file.size > maxAudioFileSize) {
    errorMessage.value = '录音文件不能超过 100 MB。'
    return
  }
  selectedRecordingFile.value = file
  uploadedRecording.value = null
  recordingReviewDetail.value = null
}

function onRecordingDrop(event: DragEvent) {
  isDraggingRecording.value = false
  selectRecordingFile(event.dataTransfer?.files?.[0])
}

async function uploadRecording() {
  if (!selectedRecordingFile.value || isUploadingRecording.value) return
  clearMessages()
  isUploadingRecording.value = true
  try {
    uploadedRecording.value = await interviewApi.uploadRecording(selectedRecordingFile.value)
    await loadRecordingReviews()
    successMessage.value = uploadedRecording.value.duplicate
      ? '检测到相同音频，已复用历史文件，没有重复上传。'
      : '录音上传成功，已加入历史录音资料库。可以开始 AI 复盘。'
  } catch (error) {
    showError(error)
  } finally {
    isUploadingRecording.value = false
  }
}

async function analyzeRecording() {
  if (!uploadedRecording.value || isAnalyzingRecording.value) return
  await analyzeSavedRecording(uploadedRecording.value.objectKey)
}

async function indexRecordingReview() {
  const detail = recordingReviewDetail.value
  const resumeId = selectedResumeId.value
  if (!detail || resumeId === null || isIndexingRecording.value) return
  if (detail.review.resumeId !== null && detail.review.resumeId !== resumeId) {
    errorMessage.value = '该录音复盘已关联其他简历，不能移动到不同的个人知识库。'
    return
  }
  clearMessages()
  isIndexingRecording.value = true
  try {
    const review = await interviewApi.indexRecordingReview(detail.review.id, resumeId)
    recordingReviewDetail.value = { ...detail, review }
    await loadRecordingReviews()
    successMessage.value = '录音复盘已加入当前简历的个人 RAG 知识库；后续模拟面试出题会检索相关历史经验。'
  } catch (error) {
    showError(error)
  } finally {
    isIndexingRecording.value = false
  }
}

async function indexAllRecordingReviews() {
  const resumeId = selectedResumeId.value
  if (resumeId === null || isIndexingAllRecordings.value) return
  clearMessages()
  isIndexingAllRecordings.value = true
  try {
    const result = await interviewApi.indexAllRecordingReviews(resumeId)
    await loadRecordingReviews()
    successMessage.value = `统一 RAG 同步完成：新增 ${result.indexed} 条录音经验，已有 ${result.alreadyIndexed} 条已是最新，失败 ${result.failed} 条，其他简历的录音跳过 ${result.skippedOtherResume} 条；另有 ${result.noReport} 个音频尚未生成复盘报告。`
  } catch (error) {
    showError(error)
  } finally {
    isIndexingAllRecordings.value = false
  }
}

function parseJsonList(value: string | null | undefined): string[] {
  if (!value) return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : []
  } catch {
    return []
  }
}

async function deleteSelectedResume() {
  const resume = selectedResume.value
  if (!resume || isDeletingResume.value) return
  const confirmed = window.confirm(`确定删除简历“${resume.originalName}”吗？关联的解析结果、模拟面试记录和报告也会一并删除。`)
  if (!confirmed) return

  clearMessages()
  isDeletingResume.value = true
  try {
    await interviewApi.deleteResume(resume.resumeId)
    parsedResumeIds.value = parsedResumeIds.value.filter((id) => id !== resume.resumeId)
    localStorage.setItem(PARSED_RESUMES_KEY, JSON.stringify(parsedResumeIds.value))
    selectedResumeId.value = null
    successMessage.value = '简历及其关联的面试记录已删除。'
    await loadResumes()
  } catch (error) {
    showError(error)
  } finally {
    isDeletingResume.value = false
  }
}

async function parseSelectedResume() {
  if (!selectedResumeId.value) return
  clearMessages()
  isParsing.value = true
  try {
    await interviewApi.parseResume(selectedResumeId.value)
    parsedResumeIds.value = [...new Set([...parsedResumeIds.value, selectedResumeId.value])]
    localStorage.setItem(PARSED_RESUMES_KEY, JSON.stringify(parsedResumeIds.value))
    successMessage.value = '简历解析完成，可以开始模拟面试了。'
    await loadResumes()
  } catch (error) {
    showError(error)
  } finally {
    isParsing.value = false
  }
}

async function startInterview() {
  if (!selectedResumeId.value) return
  clearMessages()
  isStarting.value = true
  try {
    const started = await interviewApi.start(selectedResumeId.value)
    sessionId.value = started.sessionId
    currentQuestion.value = started.firstQuestion
    answer.value = ''
    answersCount.value = 0
    report.value = null
    phase.value = 'interview'
    localStorage.setItem(ACTIVE_SESSION_KEY, JSON.stringify({
      sessionId: started.sessionId,
      resumeId: selectedResumeId.value,
      answersCount: 0,
      allAnswersSubmitted: false,
    } satisfies StoredSession))
  } catch (error) {
    showError(error)
  } finally {
    isStarting.value = false
  }
}

async function submitAnswer() {
  if (!sessionId.value || !currentQuestion.value || !answer.value.trim()) return
  clearMessages()
  isSubmitting.value = true
  try {
    const result = await interviewApi.submitAnswer(sessionId.value, answer.value.trim())
    answersCount.value += 1
    answer.value = ''
    const allAnswersSubmitted = result.interviewFinished || !result.nextQuestion
    if (allAnswersSubmitted) {
      currentQuestion.value = null
      successMessage.value = '20 道题目已全部提交，接下来生成本轮面试分析。'
    } else {
      currentQuestion.value = result.nextQuestion
    }
    if (sessionId.value && selectedResumeId.value) {
      localStorage.setItem(ACTIVE_SESSION_KEY, JSON.stringify({
        sessionId: sessionId.value,
        resumeId: selectedResumeId.value,
        answersCount: answersCount.value,
        allAnswersSubmitted,
      } satisfies StoredSession))
    }
  } catch (error) {
    showError(error)
  } finally {
    isSubmitting.value = false
  }
}

async function finishInterview() {
  if (!sessionId.value) return
  clearMessages()
  isFinishing.value = true
  try {
    const result = await interviewApi.finish(sessionId.value)
    report.value = result.report
    localStorage.removeItem(ACTIVE_SESSION_KEY)
    phase.value = 'report'
  } catch (error) {
    showError(error)
  } finally {
    isFinishing.value = false
  }
}

async function resumeInterview() {
  if (!sessionId.value) return
  clearMessages()
  isLoadingResumes.value = true
  try {
    currentQuestion.value = await interviewApi.currentQuestion(sessionId.value)
    phase.value = 'interview'
  } catch (error) {
    showError(error)
  } finally {
    isLoadingResumes.value = false
  }
}

function backToSetup() {
  localStorage.removeItem(ACTIVE_SESSION_KEY)
  phase.value = 'setup'
  sessionId.value = null
  currentQuestion.value = null
  report.value = null
  answer.value = ''
  answersCount.value = 0
  clearMessages()
  void loadResumes()
}

function startNewRound() {
  backToSetup()
}

function typeLabel(type: string) {
  const labels: Record<string, string> = {
    TECHNICAL: '技术题',
    PRINCIPLE: '原理题',
    PROJECT: '项目题',
    SCENARIO: '场景题',
  }
  return labels[type] || type || '综合题'
}

function difficultyLabel(difficulty: string) {
  const labels: Record<string, string> = { EASY: '基础', MEDIUM: '进阶', HARD: '挑战' }
  return labels[difficulty] || difficulty || '综合'
}

async function restoreActiveSession() {
  let stored: StoredSession | null = null
  try {
    stored = JSON.parse(localStorage.getItem(ACTIVE_SESSION_KEY) ?? 'null') as StoredSession | null
  } catch {
    localStorage.removeItem(ACTIVE_SESSION_KEY)
  }
  if (!stored || !Number.isInteger(stored.sessionId) || !Number.isInteger(stored.resumeId)) return

  sessionId.value = stored.sessionId
  selectedResumeId.value = stored.resumeId
  answersCount.value = Number.isInteger(stored.answersCount) ? stored.answersCount : 0
  phase.value = 'interview'
  if (stored.allAnswersSubmitted) {
    currentQuestion.value = null
    successMessage.value = '已恢复上一轮面试。所有题目已提交，可以继续生成分析报告。'
    return
  }
  try {
    currentQuestion.value = await interviewApi.currentQuestion(stored.sessionId)
  } catch (error) {
    showError(error)
  }
}

onMounted(async () => {
  await Promise.all([loadResumes(), loadRecordingReviews()])
  await restoreActiveSession()
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">IM</div>
        <div>
          <div class="brand-name">InterviewMind</div>
          <div class="brand-subtitle">AI INTERVIEW STUDIO</div>
        </div>
      </div>

      <div class="workspace-label">工作台</div>
      <nav class="side-nav" aria-label="主导航">
        <button
          class="nav-item"
          :class="{ 'nav-item-current': activeNav === '模拟面试' }"
          @click="activeNav = '模拟面试'; phase = 'setup'"
        >
          <span class="nav-icon">◉</span>
          <span>模拟面试</span>
          <span v-if="activeNav === '模拟面试'" class="nav-indicator"></span>
        </button>
        <button
          class="nav-item"
          :class="{ 'nav-item-current': activeNav === '录音复盘' }"
          @click="openRecordingReview"
        >
          <span class="nav-icon">♫</span>
          <span>录音复盘</span>
          <span v-if="activeNav === '录音复盘'" class="nav-indicator"></span>
        </button>
        <button class="nav-item" @click="activeNav = '简历管理'; phase = 'setup'">
          <span class="nav-icon">▤</span><span>简历管理</span>
        </button>
        <div class="nav-item nav-item-muted" title="后续迭代开放">
          <span class="nav-icon">⌘</span><span>面试知识库</span><span class="soon-tag">即将开放</span>
        </div>
      </nav>

      <div class="sidebar-bottom">
        <div class="tip-card">
          <div class="tip-icon">✦</div>
          <div class="tip-title">让每次练习都有收获</div>
          <p>基于你的真实简历出题，完成后获得针对性的 AI 反馈。</p>
        </div>
        <div class="profile-mini">
          <div class="avatar">你</div>
          <div class="profile-text"><strong>我的工作台</strong><span>个人面试空间</span></div>
          <span class="profile-more">···</span>
        </div>
      </div>
    </aside>

    <main class="main-content">
      <header class="topbar">
        <div class="breadcrumbs"><span>工作台</span><span class="crumb-separator">/</span><strong>{{ phase === 'setup' ? '模拟面试' : phase === 'interview' ? '正在面试' : phase === 'report' ? '面试报告' : '录音复盘' }}</strong></div>
        <div class="topbar-right"><span class="status-dot"></span><span>本地工作区</span><span class="topbar-divider"></span><span class="topbar-date">{{ new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }) }}</span></div>
      </header>

      <div class="page-wrap">
        <div v-if="errorMessage" class="alert alert-error" role="alert">
          <span class="alert-symbol">!</span><span>{{ errorMessage }}</span>
          <button class="alert-close" aria-label="关闭提示" @click="errorMessage = ''">×</button>
        </div>
        <div v-if="successMessage" class="alert alert-success" role="status">
          <span class="alert-symbol">✓</span><span>{{ successMessage }}</span>
          <button class="alert-close" aria-label="关闭提示" @click="successMessage = ''">×</button>
        </div>

        <template v-if="phase === 'setup'">
          <section class="page-heading">
            <div>
              <div class="eyebrow"><span class="eyebrow-dot"></span> YOUR NEXT CAREER MOVE</div>
              <h1>把面试准备，变成你的优势。</h1>
              <p class="page-description">从你的简历出发，进行一场真实、有反馈、可积累的模拟面试。</p>
            </div>
            <div class="heading-decoration"><div class="decoration-orbit orbit-one"></div><div class="decoration-orbit orbit-two"></div><div class="decoration-star">✦</div></div>
          </section>

          <section class="steps-card">
            <div class="step-item step-done"><div class="step-number">01</div><div><strong>选择简历</strong><span>从真实经历开始</span></div></div>
            <div class="step-connector"></div>
            <div class="step-item" :class="{ 'step-done': isSelectedParsed }"><div class="step-number">02</div><div><strong>解析简历</strong><span>提取技能与项目</span></div></div>
            <div class="step-connector"></div>
            <div class="step-item"><div class="step-number">03</div><div><strong>开始面试</strong><span>逐题回答与复盘</span></div></div>
          </section>

          <div class="content-grid">
            <section class="panel resume-panel">
              <div class="panel-heading">
                <div><div class="panel-kicker">STEP 01 · YOUR RESUME</div><h2>选择一份简历</h2><p>AI 将围绕简历中的技能和项目生成面试题。</p></div>
                <div class="panel-icon resume-icon">▤</div>
              </div>

              <div class="upload-zone" @click="uploadInput?.click()" @keydown.enter="uploadInput?.click()" tabindex="0" role="button" aria-label="上传 PDF 简历">
                <input ref="uploadInput" class="visually-hidden" type="file" accept=".pdf,application/pdf" @change="uploadResume" />
                <div class="upload-icon">↑</div>
                <div class="upload-title">{{ isUploading ? '正在上传简历…' : '上传一份新简历' }}</div>
                <div class="upload-subtitle">拖入或点击选择 PDF 文件</div>
                <div class="upload-limit">PDF · 上传至当前项目存储</div>
              </div>

              <div class="resume-list-heading"><span>已上传的简历</span><button class="text-button" :disabled="isLoadingResumes" @click="loadResumes">{{ isLoadingResumes ? '刷新中…' : '刷新列表 ↻' }}</button></div>
              <div v-if="isLoadingResumes && resumes.length === 0" class="empty-state"><span class="loading-spinner"></span><span>正在读取简历列表…</span></div>
              <div v-else-if="resumes.length === 0" class="empty-state">
                <div class="empty-illustration">▧</div><strong>还没有简历</strong><span>上传 PDF 简历后即可开始</span>
              </div>
              <div v-else class="resume-list">
                <button
                  v-for="resume in resumes"
                  :key="resume.resumeId"
                  class="resume-item"
                  :class="{ 'resume-selected': selectedResumeId === resume.resumeId }"
                  @click="selectedResumeId = resume.resumeId; clearMessages()"
                >
                  <div class="pdf-icon">PDF</div>
                  <div class="resume-info"><strong>{{ resume.originalName }}</strong><span>{{ formatFileSize(resume.fileSize) }} <i>·</i> {{ formatDate(resume.createdAt) }}</span></div>
                  <span v-if="isResumeParsed(resume)" class="resume-status parsed">已解析</span>
                  <span v-else class="resume-status">待解析</span>
                  <span class="radio-indicator">{{ selectedResumeId === resume.resumeId ? '✓' : '' }}</span>
                </button>
              </div>
              <div v-if="selectedResume" class="selected-resume-bar">
                <span class="selected-label">当前选择</span><strong>{{ selectedResume.originalName }}</strong>
                <div class="selected-resume-actions"><button class="text-button" :disabled="isDeletingResume" @click="deleteSelectedResume">{{ isDeletingResume ? '删除中…' : '删除简历' }}</button><button class="icon-button" title="清除选择" @click="selectedResumeId = null">×</button></div>              </div>
            </section>

            <section class="panel start-panel">
              <div class="panel-heading">
                <div><div class="panel-kicker">STEP 02 · PRACTICE SESSION</div><h2>准备好开始了吗？</h2><p>一轮完整的简历定制模拟面试。</p></div>
                <div class="panel-icon spark-icon">✦</div>
              </div>
              <div class="session-preview">
                <div class="preview-top"><span class="preview-label">本轮面试配置</span><span class="preview-live"><i></i> AI 驱动</span></div>
                <div class="preview-metric"><strong>20</strong><span>道定制问题</span></div>
                <div class="preview-divider"></div>
                <div class="preview-row"><span class="preview-row-icon">◷</span><div><strong>建议预留 30–45 分钟</strong><span>根据自己的节奏认真作答</span></div></div>
                <div class="preview-row"><span class="preview-row-icon">◎</span><div><strong>逐题提交，不展示参考答案</strong><span>尽可能还原真实面试体验</span></div></div>
                <div class="preview-row"><span class="preview-row-icon">✧</span><div><strong>结束后生成 AI 评价</strong><span>包含总评、薄弱点与改进建议</span></div></div>
              </div>
              <div v-if="selectedResume && !isSelectedParsed" class="parse-hint"><span>ⓘ</span><span>开始面试前，需要先解析当前简历。</span></div>
              <button v-if="selectedResume && !isSelectedParsed" class="button button-secondary button-full" :disabled="isParsing" @click="parseSelectedResume">
                <span v-if="isParsing" class="loading-spinner"></span><span>{{ isParsing ? '正在解析简历…' : '解析当前简历' }}</span><span v-if="!isParsing">→</span>
              </button>
              <button v-else class="button button-primary button-full" :disabled="!selectedResumeId || isStarting" @click="startInterview">
                <span v-if="isStarting" class="loading-spinner"></span><span>{{ isStarting ? '正在生成面试题…' : '开始模拟面试' }}</span><span v-if="!isStarting">↗</span>
              </button>
              <p class="privacy-note"><span>♧</span> 你的简历只用于当前面试流程与个性化分析。</p>
            </section>
          </div>
          <div class="bottom-note"><span>✦</span> <strong>每一次回答，都是一次进步。</strong><span>保持真实，持续复盘。</span></div>
        </template>

        <template v-else-if="phase === 'recording-review'">
          <section class="page-heading">
            <div>
              <div class="eyebrow"><span class="eyebrow-dot"></span> RECORDING REVIEW</div>
              <h1>让每一次真实面试，都成为经验。</h1>
              <p class="page-description">上传面试录音，AI 将识别问题、梳理回答，并整理优势、短板与改进建议。</p>
            </div>
          </section>

          <section class="panel recording-panel">
            <div class="panel-heading">
              <div>
                <div class="panel-kicker">STEP 01 · UPLOAD AUDIO</div>
                <h2>上传面试录音</h2>
                <p>支持 M4A、MP3、WAV、AAC、FLAC、OGG、OPUS、WEBM、MP4，单个文件最大 100 MB。</p>
              </div>
              <div class="panel-icon recording-icon">♫</div>
            </div>

            <input
              ref="recordingInput"
              class="visually-hidden"
              type="file"
              accept=".m4a,.mp3,.wav,.aac,.flac,.ogg,.opus,.webm,.mp4,audio/*,video/mp4"
              @change="selectRecordingFile(($event.target as HTMLInputElement).files?.[0]); ($event.target as HTMLInputElement).value = ''"
            />
            <div
              class="recording-dropzone"
              :class="{ 'recording-dropzone-active': isDraggingRecording }"
              role="button"
              tabindex="0"
              @click="recordingInput?.click()"
              @keydown.enter.prevent="recordingInput?.click()"
              @keydown.space.prevent="recordingInput?.click()"
              @dragenter.prevent="isDraggingRecording = true"
              @dragover.prevent="isDraggingRecording = true"
              @dragleave.prevent="isDraggingRecording = false"
              @drop.prevent="onRecordingDrop"
            >
              <div class="recording-upload-symbol">↑</div>
              <strong>{{ isDraggingRecording ? '松开鼠标即可添加录音' : '将录音文件拖拽到这里' }}</strong>
              <span>或者点击选择本地文件</span>
              <small>音频会上传到 MinIO；ASR 原文不会保存到数据库。</small>
            </div>

            <div v-if="selectedRecordingFile" class="recording-file-row">
              <div class="recording-file-icon">AUDIO</div>
              <div class="recording-file-info">
                <strong>{{ selectedRecordingFile.name }}</strong>
                <span>{{ formatFileSize(selectedRecordingFile.size) }}</span>
              </div>
              <span v-if="uploadedRecording" class="recording-file-status">已上传</span>
              <button
                v-if="!isUploadingRecording && !isAnalyzingRecording"
                class="icon-button"
                title="移除所选文件"
                @click="selectedRecordingFile = null; uploadedRecording = null; recordingReviewDetail = null"
              >×</button>
            </div>

            <div class="recording-actions">
              <button
                v-if="!uploadedRecording"
                class="button button-primary"
                :disabled="!selectedRecordingFile || isUploadingRecording"
                @click="uploadRecording"
              >
                <span v-if="isUploadingRecording" class="loading-spinner"></span>
                {{ isUploadingRecording ? '正在上传录音…' : '上传录音' }}
              </button>
              <button
                v-else
                class="button button-primary"
                :disabled="isAnalyzingRecording"
                @click="analyzeRecording"
              >
                <span v-if="isAnalyzingRecording" class="loading-spinner"></span>
                {{ isAnalyzingRecording ? '正在转写并分析，请耐心等待…' : '开始 AI 复盘' }}
              </button>
            </div>

            <div v-if="isAnalyzingRecording" class="recording-processing-note" role="status">
              正在执行 ASR 转写与 LLM 结构化分析。较长录音可能需要几分钟，请保持页面打开。完成后只会保存结构化复盘结果。
            </div>
          </section>

          <section class="panel recording-history-panel">
            <div class="panel-heading">
              <div><div class="panel-kicker">AUDIO LIBRARY</div><h2>历史录音资料库</h2><p>所有已完成的录音复盘可统一沉淀到同一份个人 RAG；刷新页面后仍可查看。</p></div>
              <div class="recording-library-actions">
                <button class="button button-primary" :disabled="selectedResumeId === null || isIndexingAllRecordings" @click="indexAllRecordingReviews">
                  <span v-if="isIndexingAllRecordings" class="loading-spinner"></span>
                  {{ isIndexingAllRecordings ? '正在同步全部经验…' : '同步全部录音经验到 RAG' }}
                </button>
                <button class="text-button" :disabled="isLoadingRecordingReviews" @click="loadRecordingReviews">{{ isLoadingRecordingReviews ? '刷新中…' : '刷新列表 ↻' }}</button>
              </div>
            </div>
            <div v-if="recordingLibrary.length === 0 && !isLoadingRecordingReviews" class="empty-state">还没有录音文件。上传后会自动保存在这里。</div>
            <article v-for="item in recordingLibrary" :key="item.objectKey" class="recording-question recording-history-item">
              <div class="recording-question-number">♫</div>
              <div class="recording-question-body">
                <h3>{{ item.originalName }}</h3>
                <p>{{ item.fileSize !== null ? formatFileSize(item.fileSize) : '历史录音' }}<span v-if="item.uploadedAt"> · {{ formatDate(item.uploadedAt) }}</span></p>
                <p>报告状态：{{ item.review ? (item.review.processingStatus === 'COMPLETED' ? '已生成报告' : '报告处理中/待重试') : '尚未生成报告' }}</p>
                <p v-if="item.review?.summary" class="recording-library-summary">{{ item.review.summary }}</p>
                <p v-if="item.review?.vectorStatus === 'INDEXED' && item.review.resumeId !== null" class="recording-library-rag-status">已加入简历 #{{ item.review.resumeId }} 的 RAG 知识库</p>
              </div>
              <div class="recording-library-actions">
                <button v-if="item.review" class="button button-secondary" @click="openSavedRecordingReview(item.review.id)">查看报告</button>
                <button v-else class="button button-primary" :disabled="isAnalyzingRecording" @click="analyzeSavedRecording(item.objectKey)">
                  <span v-if="analyzingObjectKey === item.objectKey" class="loading-spinner"></span>
                  {{ analyzingObjectKey === item.objectKey ? '正在分析…' : '生成报告' }}
                </button>
              </div>
            </article>
          </section>

          <section v-if="recordingReviewDetail" ref="recordingResultSection" class="recording-result">
            <section class="panel recording-summary-panel">
              <div class="panel-kicker">AI REVIEW SUMMARY</div>
              <h2>本次录音复盘</h2>
              <p class="recording-summary-text">{{ recordingReviewDetail.review.summary || '暂无总结。' }}</p>
              <div class="recording-rag-actions">
                <p v-if="recordingReviewDetail.review.vectorStatus === 'INDEXED' && recordingReviewDetail.review.resumeId !== null" class="muted-copy">
                  <strong>已加入个人 RAG 知识库</strong>
                  <span v-if="recordingReviewDetail.review.resumeId"> · 关联简历 #{{ recordingReviewDetail.review.resumeId }}</span>
                </p>
                <template v-else>
                  <p class="muted-copy">
                    将这次复盘加入所选简历的个人知识库，之后生成模拟面试题时会参考这些历史回答与薄弱点。
                    <span v-if="recordingReviewDetail.review.resumeId">当前已关联简历 #{{ recordingReviewDetail.review.resumeId }}。</span>
                  </p>
                  <button
                    class="button button-primary"
                    :disabled="selectedResumeId === null || isIndexingRecording || (recordingReviewDetail.review.resumeId !== null && recordingReviewDetail.review.resumeId !== selectedResumeId)"
                    @click="indexRecordingReview"
                  >
                    <span v-if="isIndexingRecording" class="loading-spinner"></span>
                    {{ isIndexingRecording ? '正在建立向量索引…' : recordingReviewDetail.review.vectorStatus === 'FAILED' ? '重试加入个人知识库' : '加入个人 RAG 知识库' }}
                  </button>
                  <p v-if="selectedResumeId === null" class="muted-copy">请先在“模拟面试”页面选择一份简历。</p>
                  <p v-else-if="recordingReviewDetail.review.resumeId !== null && recordingReviewDetail.review.resumeId !== selectedResumeId" class="muted-copy">当前所选简历与该复盘已关联的简历不同，请切换回对应简历。</p>
                </template>
              </div>
              <div class="recording-result-columns">
                <div>
                  <h3>表现优势</h3>
                  <ul v-if="parseJsonList(recordingReviewDetail.review.strengths).length">
                    <li v-for="item in parseJsonList(recordingReviewDetail.review.strengths)" :key="item">{{ item }}</li>
                  </ul>
                  <p v-else class="muted-copy">暂无内容</p>
                </div>
                <div>
                  <h3>待提升的地方</h3>
                  <ul v-if="parseJsonList(recordingReviewDetail.review.weakPoints).length">
                    <li v-for="item in parseJsonList(recordingReviewDetail.review.weakPoints)" :key="item">{{ item }}</li>
                  </ul>
                  <p v-else class="muted-copy">暂无内容</p>
                </div>
              </div>
              <div class="recording-suggestions">
                <h3>改进建议</h3>
                <ul v-if="parseJsonList(recordingReviewDetail.review.suggestions).length">
                  <li v-for="item in parseJsonList(recordingReviewDetail.review.suggestions)" :key="item">{{ item }}</li>
                </ul>
                <p v-else class="muted-copy">暂无内容</p>
              </div>
            </section>

            <section class="panel recording-questions-panel">
              <div class="panel-heading">
                <div><div class="panel-kicker">QUESTION BY QUESTION</div><h2>识别到的面试问题</h2></div>
                <span class="review-count">{{ recordingReviewDetail.questions.length }} 道题</span>
              </div>
              <article v-for="item in recordingReviewDetail.questions" :key="item.id" class="recording-question">
                <div class="recording-question-number">{{ String(item.questionNo).padStart(2, '0') }}</div>
                <div class="recording-question-body">
                  <h3>{{ item.question || '未识别到问题文本' }}</h3>
                  <p v-if="item.candidateAnswer"><strong>你的回答</strong>{{ item.candidateAnswer }}</p>
                  <p v-if="item.answerSummary"><strong>回答概括</strong>{{ item.answerSummary }}</p>
                  <p v-if="item.referenceAnswer"><strong>参考答案</strong>{{ item.referenceAnswer }}</p>
                  <p v-if="item.improvedAnswer"><strong>优化示例</strong>{{ item.improvedAnswer }}</p>
                  <p v-if="parseJsonList(item.missingPoints).length"><strong>待补充</strong>{{ parseJsonList(item.missingPoints).join('；') }}</p>
                </div>
              </article>
            </section>
          </section>
        </template>

        <template v-else-if="phase === 'interview'">
          <section class="interview-heading">
            <div><div class="eyebrow"><span class="eyebrow-dot"></span> LIVE INTERVIEW</div><h1>专注你的每一次回答。</h1><p class="page-description">按照自己的思路作答，就像正在面对一位真实的面试官。</p></div>
            <button class="button button-quiet" @click="backToSetup">退出本轮</button>
          </section>
          <section class="interview-layout">
            <div class="interview-main panel">
              <div class="question-toolbar"><span class="live-badge"><i></i> 面试进行中</span><span class="session-id">SESSION #{{ sessionId }}</span></div>
              <div class="question-progress">
                <div class="progress-copy"><span>QUESTION PROGRESS</span><strong v-if="currentQuestion">{{ String(currentQuestion.questionNo).padStart(2, '0') }} <i>/</i> {{ String(currentQuestion.totalQuestions).padStart(2, '0') }}</strong><strong v-else>20 <i>/</i> 20</strong></div>
                <div class="progress-track"><div class="progress-value" :style="{ width: (currentQuestion ? ((currentQuestion.questionNo - 1) / currentQuestion.totalQuestions) * 100 : 100) + '%' }"></div></div>
                <div class="progress-foot"><span>开始</span><span>完成全部题目，查看分析</span></div>
              </div>

              <template v-if="currentQuestion">
                <div class="question-tags"><span>{{ typeLabel(currentQuestion.type) }}</span><span>{{ difficultyLabel(currentQuestion.difficulty) }}</span><span v-if="currentQuestion.knowledgePoint">{{ currentQuestion.knowledgePoint }}</span></div>
                <div class="question-label">面试官向你提问</div>
                <h2 class="question-text">{{ currentQuestion.question }}</h2>
                <div class="answer-label"><label for="answer-box">你的回答</label><span>尽可能结合实际项目与具体细节</span></div>
                <textarea id="answer-box" v-model="answer" class="answer-textarea" placeholder="在这里输入你的回答…&#10;&#10;可以从背景、思路、具体行动和最终结果几个方面展开。" :disabled="isSubmitting" @keydown.ctrl.enter="submitAnswer"></textarea>
                <div class="answer-footer"><span>{{ answer.trim().length }} 字 <i>·</i> 支持 Ctrl + Enter 提交</span><button class="button button-primary" :disabled="!answer.trim() || isSubmitting" @click="submitAnswer"><span v-if="isSubmitting" class="loading-spinner"></span><span>{{ isSubmitting ? '正在提交…' : '提交回答' }}</span><span v-if="!isSubmitting">→</span></button></div>
              </template>
              <template v-else>
                <div class="all-done"><div class="done-icon">✓</div><div class="panel-kicker">ALL ANSWERS SUBMITTED</div><h2>本轮问题已经全部完成。</h2><p>你已提交 {{ answersCount }} 道回答。现在生成整场面试的 AI 评价，看看哪些地方做得好、下一步可以如何提升。</p><button class="button button-primary" :disabled="isFinishing" @click="finishInterview"><span v-if="isFinishing" class="loading-spinner"></span>{{ isFinishing ? 'AI 正在分析你的回答…' : '生成面试分析报告' }} <span v-if="!isFinishing">↗</span></button></div>
              </template>
            </div>

            <aside class="interview-aside">
              <div class="panel aside-card">
                <div class="aside-title"><span class="aside-title-icon">✦</span><strong>本轮面试</strong></div>
                <div class="aside-stat"><div><span>已完成题目</span><strong>{{ answersCount }}<small> / 20</small></strong></div><div class="mini-progress"><svg viewBox="0 0 60 60" aria-label="答题进度"><circle class="mini-progress-bg" cx="30" cy="30" r="24"></circle><circle class="mini-progress-fg" cx="30" cy="30" r="24" :style="{ strokeDashoffset: 150.8 * (1 - answersCount / 20) }"></circle></svg><span>{{ Math.round(answersCount / 20 * 100) }}%</span></div></div>
                <div class="aside-divider"></div>
                <div class="aside-info-row"><span>面试模式</span><strong>简历定制</strong></div>
                <div class="aside-info-row"><span>答题方式</span><strong>逐题作答</strong></div>
                <div class="aside-info-row"><span>参考答案</span><strong>结束后复盘</strong></div>
              </div>
              <div class="panel encouragement-card"><div class="encouragement-art">✧</div><strong>给自己一点思考时间</strong><p>面试中，清晰的思路往往比快速回答更重要。用具体事实支撑你的观点。</p></div>
              <button v-if="currentQuestion" class="resume-session-button" @click="resumeInterview" :disabled="isLoadingResumes">重新加载当前题目 ↻</button>
            </aside>
          </section>
        </template>

        <template v-else-if="phase === 'report' && report">
          <section class="report-heading"><div><div class="eyebrow"><span class="eyebrow-dot"></span> YOUR INTERVIEW REVIEW</div><h1>这次表现，值得认真复盘。</h1><p class="page-description">报告基于本轮实际回答生成，找出优势，也找到下一步的提升方向。</p></div><button class="button button-secondary" @click="startNewRound">再来一轮 ↗</button></section>
          <section class="report-hero panel">
            <div class="report-score-wrap"><div class="score-caption">本轮综合评分</div><div class="score-ring"><div><strong>{{ report.totalScore ?? '—' }}</strong><span>综合得分</span></div></div><div class="score-foot">满分 100 分 · SESSION #{{ report.sessionId }}</div></div>
            <div class="report-summary"><div class="report-summary-label"><span class="summary-spark">✦</span> AI 面试总评</div><h2>{{ report.totalScore >= 85 ? '表现出色，继续保持。' : report.totalScore >= 70 ? '基础不错，还有提升空间。' : '找到差距，就是进步的开始。' }}</h2><p>{{ report.summary || '本轮评价已生成。你可以结合下方逐题反馈继续复盘。' }}</p><div class="report-date">完成时间 · {{ report.finishedAt ? new Date(report.finishedAt).toLocaleString('zh-CN') : '刚刚' }}</div></div>
          </section>
          <div class="report-columns">
            <section class="panel feedback-panel"><div class="feedback-heading"><span class="feedback-icon strength-icon">↗</span><div><h3>你的优势</h3><p>本轮表现值得保持的部分</p></div></div><ul v-if="report.strengths?.length" class="feedback-list"><li v-for="item in report.strengths" :key="item"><span>✓</span>{{ item }}</li></ul><p v-else class="muted-copy">报告中没有返回具体优势条目。</p></section>
            <section class="panel feedback-panel"><div class="feedback-heading"><span class="feedback-icon weakness-icon">◎</span><div><h3>待提升的地方</h3><p>下次回答可以关注这些问题</p></div></div><ul v-if="report.weakPoints?.length" class="feedback-list"><li v-for="item in report.weakPoints" :key="item"><span>!</span>{{ item }}</li></ul><p v-else class="muted-copy">报告中没有返回具体薄弱点。</p></section>
          </div>
          <section class="panel suggestions-panel"><div class="panel-kicker">NEXT ACTIONS</div><h2>下一步，做得更好</h2><div v-if="report.suggestions?.length" class="suggestion-list"><div v-for="(item, index) in report.suggestions" :key="item" class="suggestion-item"><span class="suggestion-number">{{ String(index + 1).padStart(2, '0') }}</span><span>{{ item }}</span></div></div><p v-else class="muted-copy">当前报告没有返回后续建议。</p></section>
          <section class="panel question-review-panel"><div class="panel-heading"><div><div class="panel-kicker">QUESTION BY QUESTION</div><h2>逐题反馈</h2><p>查看每道题的得分、分析与改进建议。</p></div><div class="review-count">{{ report.questionEvaluations?.length ?? 0 }} 道题</div></div><div v-if="report.questionEvaluations?.length" class="evaluation-list"><article v-for="item in report.questionEvaluations" :key="item.questionNo" class="evaluation-item"><div class="evaluation-number">{{ String(item.questionNo).padStart(2, '0') }}</div><div class="evaluation-content"><div class="evaluation-title">第 {{ item.questionNo }} 题 · {{ item.question || '面试题' }}</div><div class="evaluation-detail"><strong>你的回答</strong><span>{{ item.userAnswer || '未记录回答' }}</span></div><div class="evaluation-detail"><strong>参考答案</strong><span>{{ item.referenceAnswer || '本题未生成参考答案' }}</span></div><p>{{ item.analysis || '暂无详细分析。' }}</p><div v-if="item.weakPoints?.length" class="evaluation-detail"><strong>需要补足</strong><span>{{ item.weakPoints.join('；') }}</span></div><div v-if="item.suggestions?.length" class="evaluation-detail"><strong>改进建议</strong><span>{{ item.suggestions.join('；') }}</span></div></div><div class="evaluation-score"><strong>{{ item.score ?? '—' }}</strong><span>分</span></div></article></div><p v-else class="muted-copy">没有逐题评价数据。</p></section>
          <div class="report-bottom"><span><span class="eyebrow-dot"></span> 报告已由后端面试评价流程返回</span><button class="button button-primary" @click="startNewRound">开始新一轮面试 <span>↗</span></button></div>
        </template>

        <footer class="page-footer"><span>© 2026 InterviewMind AI</span><span>把经验积累下来，让下一次表现更好。</span></footer>
      </div>
    </main>
  </div>
</template>