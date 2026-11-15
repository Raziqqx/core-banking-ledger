{{- define "cbl.name" -}}{{ .Chart.Name }}{{- end -}}
{{- define "cbl.fullname" -}}{{ printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}{{- end -}}
{{- define "cbl.labels" -}}
app.kubernetes.io/name: {{ include "cbl.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}
{{- define "cbl.selector" -}}
app.kubernetes.io/name: {{ include "cbl.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "cbl.secretName" -}}{{ .Values.secret.existingSecret | default (include "cbl.fullname" .) }}{{- end -}}
