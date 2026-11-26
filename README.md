# Claude Relay — 맥미니 Claude Code ↔ 안드로이드 중계

24시간 켜져 있는 맥미니의 **Claude Code 세션별 입력/출력**을 안드로이드 폰으로 실시간 중계하고,
폰에서 세션에 메시지를 보내 작업을 이어갈 수 있게 해 주는 앱입니다.

```
┌────────────── Mac mini ──────────────┐          ┌──── Android ────┐
│ ~/.claude/projects/*/<세션ID>.jsonl  │──tail──┐ │                 │
│        ▲ (터미널/앱 어디서 실행하든 기록) │        │ │  세션 목록      │
│        │                             │  relay-server ── WS/HTTP ──▶│  실시간 대화    │
│ claude -p --resume <세션ID>  ◀───────│────────┘ │  메시지 전송/중지 │
└──────────────────────────────────────┘          └─────────────────┘
```

- **출력 중계**: 세션 파일(`~/.claude/projects/…/*.jsonl`)을 실시간으로 tail 하므로, 맥 터미널에서 직접 돌리는 세션도 폰에서 그대로 보입니다. (사용자 입력, Claude 응답, 도구 호출·결과, 생각 블록)
- **입력 중계**: 폰에서 보낸 메시지는 `claude -p --resume <세션ID>`로 같은 세션에 이어 붙습니다. 응답은 토큰 단위로 실시간 스트리밍됩니다.
- 새 세션 시작(작업 폴더 선택), 실행 중지, 권한 모드 선택, 작업 완료 알림 지원.

## 1. 맥미니: 중계 서버 설치

필요: Node.js 18+, Claude Code(`claude`)가 설치되고 로그인된 상태.

```bash
git clone <이 저장소> ~/claude-relay && cd ~/claude-relay/relay-server
./install-macos.sh          # launchd 등록 → 로그인 시 자동 시작, 죽으면 자동 재시작
sudo pmset -a sleep 0 disksleep 0   # 맥이 잠들지 않게 (한 번만)
```

설치가 끝나면 **서버 주소와 토큰**이 출력됩니다. 다시 보려면:

```bash
grep -E "URL|token" ~/.claude-relay/server.log | tail -4
cat ~/.claude-relay/config.json
```

수동 실행(테스트용): `npm install && npm start` · 제거: `./uninstall-macos.sh`

### 설정 (`~/.claude-relay/config.json`, 또는 환경변수)

| 키 | 환경변수 | 기본값 | 설명 |
|---|---|---|---|
| `port` | `RELAY_PORT` | `8787` | 포트 |
| `host` | `RELAY_HOST` | `0.0.0.0` | 바인드 주소 (Tailscale IP만 열려면 `100.x.y.z`) |
| `token` | `RELAY_TOKEN` | 자동 생성 | 앱 인증 토큰 |
| `claudeBin` | `CLAUDE_BIN` | `claude` | claude 실행 파일 경로 |
| `allowedRoots` | `RELAY_ALLOWED_ROOTS` (`:` 구분) | `[홈 폴더]` | 새 세션을 만들 수 있는 폴더 범위 |
| `permissionModes` | – | `["acceptEdits","default","plan","bypassPermissions"]` | 앱에서 고를 수 있는 권한 모드 (첫 번째가 기본) |
| `extraArgs` | – | `[]` | `claude`에 추가로 넘길 인자 (예: `["--allowedTools","Bash(git:*)"]`) |

설정을 바꾼 뒤에는 `launchctl kickstart -k gui/$(id -u)/com.claude-relay.server` 로 재시작하세요.

## 2. 폰에서 맥미니에 접속하기

집 밖에서도 쓰려면 **[Tailscale](https://tailscale.com)** 을 권장합니다 (맥미니와 폰에 설치하고 같은 계정으로 로그인).
그러면 `http://100.x.y.z:8787` 같은 주소로 어디서든 암호화된 사설망으로 접속됩니다.
포트를 인터넷에 직접 공개하는 것은 권장하지 않습니다(토큰만으로 보호되고 TLS가 없습니다).
같은 와이파이에서만 쓸 거라면 맥의 LAN IP(`http://192.168.x.x:8787`)도 됩니다.

## 3. 안드로이드 앱

### APK 받기
GitHub → **Actions → Android APK** 워크플로 → 최신 실행의 **claude-relay-apk** 아티팩트를 받아 폰에 설치합니다.
(직접 빌드: Android Studio로 `android-app/` 열기, 또는 `cd android-app && ./gradlew assembleRelease`)

### 사용법
1. 첫 실행 시 **서버 주소**와 **토큰** 입력 → 저장하고 연결 (오른쪽 위 점: 초록=연결됨)
2. **세션 목록**: 최근 수정 순. 실행 중인 세션은 스피너 표시. 당겨서 새로고침.
3. 세션을 누르면 대화 기록 + 실시간 출력. 아래 입력창으로 메시지 전송, 실행 중이면 ■ 버튼으로 중지.
4. 입력창 위 칩으로 **권한 모드** 선택
   - 편집 자동승인(`acceptEdits`): 파일 편집은 자동 허용, 그 외 승인이 필요한 도구는 거부됨
   - 기본(`default`): 승인이 필요한 도구는 모두 거부됨 (원격이라 승인 프롬프트를 띄울 수 없음)
   - 계획(`plan`): 읽기/계획만
   - 모든 권한(`bypassPermissions`): 모든 도구 허용 — 신뢰하는 작업에만 사용
5. **새 세션**: 맥미니의 작업 폴더(절대경로, 기존 프로젝트 목록에서 선택 가능)와 첫 메시지를 입력.
6. 앱이 백그라운드에 있을 때 작업이 끝나면 알림이 옵니다 (앱 프로세스가 살아 있는 동안).

## 주의 사항
- 맥 터미널에서 **같은 세션을 대화형으로 열어 둔 채** 폰에서도 보내면 두 프로세스가 같은 세션에 동시에 기록하게 됩니다. 한쪽에서만 입력하세요. (보기만 하는 것은 문제없습니다.)
- 원격 실행(`-p`)에서는 권한 승인 대화상자를 띄울 수 없으므로, 필요한 도구는 권한 모드나 `extraArgs`의 `--allowedTools`로 미리 허용해야 합니다.
- 토큰은 `~/.claude-relay/config.json`(권한 600)에 저장됩니다. 노출되면 파일에서 지우고 재시작하면 새로 생성됩니다.

## 서버 API (참고)

인증: `Authorization: Bearer <token>` 또는 `?token=`

| | |
|---|---|
| `GET /api/health` | 상태 확인 (인증 불필요) |
| `GET /api/sessions` | 세션 목록 |
| `GET /api/sessions/:id/messages?limit=300` | 대화 기록 + 파일 offset |
| `GET /api/projects` | 작업 폴더 목록 |
| `WS /ws` | `subscribe{sessionId,fromOffset}` · `unsubscribe` · `send{sessionId?,cwd?,text,permissionMode?,model?}` · `interrupt{sessionId}` → `message` · `delta` · `run_started` · `run_finished` · `sent` · `error` |
