export type Row = Record<string, unknown>

export type Membership = {
  school_id: string
  school_code: string
  school_name: string
  role: 'SCHOOL_ADMIN' | 'TEACHER' | 'PARENT' | 'STUDENT' | string
}

export type CurrentUser = {
  user: { id: string; email: string; full_name: string; platform_role: string }
  memberships: Membership[]
}

export type TeachingAssignment = {
  teaching_assignment_id: string
  classroom_id: string
  classroom_name: string
  subject_name: string
}

export type Student = { id: string; student_code: string; full_name: string; classroom_name?: string | null }
export type Assessment = { id: string; title: string; status: 'DRAFT' | 'SUBMITTED' | 'LOCKED' | string; max_score: number | string; version?: number }
export type LeaveRequest = { id: string; student_name: string; start_date: string; end_date: string; reason: string; status: string }
export type Preference = { category: string; inAppEnabled: boolean; realtimeEnabled: boolean }
export type NotificationPage = { items: Row[]; nextCursor?: { beforeCreatedAt: string; beforeId: string } | null }

export type ReportRange = {
  from: string
  to: string
  label: string
  academicYearId?: string | null
  semesterId?: string | null
  granularity: 'DAY' | 'WEEK' | string
}

export type AttendanceMetrics = {
  total: number
  present: number
  absent: number
  excused: number
  late: number
  earlyLeave: number
  attendanceRate: number
  absenceRate: number
}

export type AttendanceTrendPoint = { date: string; total: number; absent: number; late: number; attendanceRate: number }
export type ScoreMetrics = { publishedScores: number; scoredStudents: number; averageScore: number; averagePercent: number }
export type ScoreTrendPoint = { date: string; averageScore: number; scores: number }
export type ScoreBand = { band: string; count: number }
export type SubjectPerformance = { subjectId: string; subjectName: string; averageScore: number; students: number; assessments: number }
export type ClassPerformance = { classroomId: string; classroomName: string; averageScore: number; attendanceRate: number }

export type RiskFactor = {
  code: 'ABSENCE' | 'SCORE' | 'LATE' | string
  label: string
  value: number
  threshold: number
  direction: 'ABOVE' | 'BELOW' | string
  severity: 'HIGH' | 'MEDIUM' | string
}

export type RiskStudent = {
  studentId: string
  fullName: string
  classroomName?: string
  attendanceTotal: number
  absentCount: number
  lateCount: number
  absencePercent: number
  latePercent: number
  averageScore?: number | null
  riskLevel: 'HIGH' | 'MEDIUM' | 'LOW'
  factors: RiskFactor[]
}

export type SchoolDashboard = {
  scope: 'SCHOOL'
  range: ReportRange
  headcount: { students: number; teachers: number; guardians: number; classrooms: number }
  attendance: AttendanceMetrics
  attendanceTrend: AttendanceTrendPoint[]
  scores: ScoreMetrics
  scoreTrend: ScoreTrendPoint[]
  scoreDistribution: ScoreBand[]
  subjectPerformance: SubjectPerformance[]
  classPerformance: ClassPerformance[]
  leave: { total: number; submitted: number; approved: number; rejected: number }
  communication: { announcements: number; messages: number; myUnread: number }
  attention: RiskStudent[]
  recentAbsences: Row[]
}

export type TeacherDashboard = {
  scope: 'TEACHER'
  range: ReportRange
  headcount: { assignments: number; classrooms: number; students: number }
  todayClasses: Row[]
  missingAttendance: Row[]
  workQueue: { pendingLeave: number; draftAssessments: number; missingAttendance: number }
  attendance: AttendanceMetrics
  attendanceTrend: AttendanceTrendPoint[]
  scores: ScoreMetrics
  scoreTrend: ScoreTrendPoint[]
  subjectPerformance: SubjectPerformance[]
  attention: RiskStudent[]
  recentAbsences: Row[]
}

export type StudentReport = {
  range: ReportRange
  student: Row
  attendance: AttendanceMetrics
  attendanceTrend: AttendanceTrendPoint[]
  subjectAverages: { subjectId: string; subjectName: string; weightedAverage: number; publishedScores: number }[]
  recentScores: Row[]
  recentAttendance: Row[]
  recentComments: Row[]
  risk: Omit<RiskStudent, 'studentId' | 'fullName' | 'classroomName'>
}
