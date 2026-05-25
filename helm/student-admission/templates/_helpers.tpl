{{/*
App name
*/}}
{{- define "student-admission.appName" -}}
{{ .Release.Name }}-app
{{- end }}

{{/*
Postgres name
*/}}
{{- define "student-admission.postgresName" -}}
{{ .Release.Name }}-postgres
{{- end }}

{{/*
Postgres service name (used as DB hostname)
*/}}
{{- define "student-admission.postgresServiceName" -}}
{{ .Release.Name }}-postgres-svc
{{- end }}

{{/*
Common labels
*/}}
{{- define "student-admission.labels" -}}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}
