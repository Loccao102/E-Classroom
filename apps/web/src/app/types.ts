export type Row = Record<string, unknown>

export type Membership = {
  school_id: string
  school_code: string
  school_name: string
  role: 'SCHOOL_ADMIN' | 'TEACHER' | 'PARENT' | 'STUDENT' | string
}

export type CurrentUser = {
  user: {
    id: string
    email: string
    full_name: string
    platform_role: string
  }
  memberships: Membership[]
}

export type TeachingAssignment = {
  teaching_assignment_id: string
  classroom_id: string
  classroom_name: string
  subject_name: string
}

export type Student = {
  id: string
  student_code: string
  full_name: string
  classroom_name?: string | null
}

export type Assessment = {
  id: string
  title: string
  status: 'DRAFT' | 'SUBMITTED' | 'LOCKED' | string
  max_score: number | string
  version?: number
}

export type LeaveRequest = {
  id: string
  student_name: string
  start_date: string
  end_date: string
  reason: string
  status: string
}

export type Preference = {
  category: string
  inAppEnabled: boolean
  realtimeEnabled: boolean
}

export type NotificationPage = {
  items: Row[]
  nextCursor?: { beforeCreatedAt: string; beforeId: string } | null
}
