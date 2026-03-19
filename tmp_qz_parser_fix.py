from pathlib import Path
p = Path(r'd:\nextjs-course-selector-app\course_grabber_script\cloud_grab_server\engine\qz_adapter.go')
text = p.read_text(encoding='utf-8')
text = text.replace('teacherRe := regexp.MustCompile(`teacher[:：]\\s*([^<]+)|教师[:：]\\s*([^<]+)`)','teacherRe := regexp.MustCompile(`(?i)(?:teacher|教师)[:：]\\s*([^<]+)`)')
text = text.replace('\n\t\tperiod := strings.TrimSpace(spaceRe.ReplaceAllString(tagRe.ReplaceAllString(cells[0][1], " "), " "))\n\t\tif !strings.Contains(period, "第") {\n\t\t\tcontinue\n\t\t}\n', '\n\t\tperiod := strings.TrimSpace(spaceRe.ReplaceAllString(tagRe.ReplaceAllString(cells[0][1], " "), " "))\n\t\tif period == "" {\n\t\t\tcontinue\n\t\t}\n')
text = text.replace('if teacherMatch := teacherRe.FindStringSubmatch(cell[1]); len(teacherMatch) >= 3 {\n\t\t\t\tif teacherMatch[1] != "" {\n\t\t\t\t\tteacher = strings.TrimSpace(teacherMatch[1])\n\t\t\t\t} else {\n\t\t\t\t\tteacher = strings.TrimSpace(teacherMatch[2])\n\t\t\t\t}\n\t\t\t}\n', 'if teacherMatch := teacherRe.FindStringSubmatch(cell[1]); len(teacherMatch) >= 2 {\n\t\t\t\tteacher = strings.TrimSpace(teacherMatch[1])\n\t\t\t}\n')
p.write_text(text, encoding='utf-8')
