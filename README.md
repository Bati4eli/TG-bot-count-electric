# Бот учёта электроэнергии Т1/Т2

Telegram-бот на Kotlin для учёта двухтарифного счётчика в СНТ. У каждого пользователя — свой участок, тарифы и история показаний. Данные хранятся в SQLite.

Бот умеет принимать и редактировать последнее показание, формировать сообщения для банка и председателя, экспортировать и импортировать историю Excel. Импорт проверяет формат, даты и рост показаний, затем заменяет только историю загрузившего файл пользователя.

## Модули

- **Основное приложение** — Telegram-бот и SQLite. При старте и ежедневно в 03:00 по Москве запрашивает официальные тарифы, сохраняет последнюю успешную пару в своей БД и рассылает уведомления при изменении.
- **`tariff-provider`** — закрытый HTTP-сервис официальных тарифов. У него нет расписания: он ждёт запрос от основного приложения с московской датой, по запросу читает официальный калькулятор и держит одну пару тарифов для этой даты только в памяти. Доступ защищён заголовком `Authorization: Bearer`.

Такое разделение не даёт боту обращаться к Мосэнергосбыту напрямую. Если сервис тарифов временно недоступен, бот продолжает работать с последней парой, сохранённой в SQLite.

## Локальный запуск через Docker Compose

Нужны Docker Desktop и токен от [@BotFather](https://t.me/BotFather).

```powershell
Copy-Item .env.example .env
```

В файле `.env` замените оба значения:

```dotenv
BOT_TOKEN=123456:реальный_токен_бота
TARIFF_SERVICE_TOKEN=длинный_случайный_общий_секрет
```

Запуск обоих модулей:

```powershell
docker compose up -d --build
docker compose logs -f
```

Остановка без удаления показаний:

```powershell
docker compose down
```

SQLite основного приложения хранится в Docker volume `electricity_data`. Сервис тарифов намеренно не имеет volume: его кэш существует только пока работает контейнер.

## Локальная разработка

```powershell
# Основное приложение
$env:BOT_TOKEN = "123456:реальный_токен_бота"
$env:TARIFF_SERVICE_URL = "http://localhost:8080/v1/tariffs"
$env:TARIFF_SERVICE_TOKEN = "локальный-секрет"
.\gradlew.bat run

# В отдельном окне — сервис тарифов
$env:TARIFF_SERVICE_TOKEN = "локальный-секрет"
.\gradlew.bat :tariff-provider:run
```

Проверки:

```powershell
.\gradlew.bat test
.\gradlew.bat :tariff-provider:integrationTest
```

Вторая команда — интеграционный тест официального калькулятора; она не запускается в обычной сборке.

## Развёртывание в Amvera

Создайте два приложения из одного репозитория: основное приложение и сервис тарифов. В каждом приложении достаточно одной реплики.

### Основное приложение

Использует корневые `Dockerfile` и `amvera.yml`.

Переменные Amvera:

```text
BOT_TOKEN=<токен BotFather>                         # секрет
ELECTRICITY_DB=/data/electricity.db
TARIFF_SERVICE_URL=https://<домен-сервиса>/v1/tariffs
TARIFF_SERVICE_TOKEN=<тот же общий секрет>          # секрет
```

### Сервис тарифов

Для него используйте конфигурацию [`tariff-provider/amvera.yml`](tariff-provider/amvera.yml) и Dockerfile `tariff-provider/Dockerfile`.

Переменные Amvera:

```text
PORT=8080
TARIFF_SERVICE_TOKEN=<тот же общий секрет>          # секрет
```

В разделе «Домены» создайте бесплатный HTTPS-домен и укажите его в `TARIFF_SERVICE_URL` основного приложения. Секрет никогда не добавляется в URL, Git или README.

### Отправка новой версии

Один раз добавьте remotes, подставив SSH-адреса репозиториев Amvera из панели:

```powershell
git remote add amvera-bot <SSH-адрес-репозитория-основного-приложения>
git remote add amvera-tariffs <SSH-адрес-репозитория-сервиса-тарифов>
```

Обычная версия основного приложения:

```powershell
.\gradlew.bat test
git add <файлы>
git commit -m "feat: описание изменения"
git push origin master
git push amvera-bot master
```

Для сервиса тарифов нужен отдельный deploy-branch, потому что Amvera читает `amvera.yml` из корня репозитория. Создайте его один раз:

```powershell
git switch -c tariff-deploy
Copy-Item tariff-provider/amvera.yml amvera.yml
git add amvera.yml
git commit -m "ci: configure tariff service deployment"
git push amvera-tariffs tariff-deploy:master
git switch master
```

После следующих изменений обновляйте сервис так:

```powershell
git switch tariff-deploy
git merge master
git push amvera-tariffs tariff-deploy:master
git switch master
```

После каждого push Amvera пересобирает и перезапускает соответствующее приложение. Проверяйте статус «Запущено» и журнал приложения в панели.

## Формат Excel

Кнопка «Скачать историю Excel» формирует файл по `шаблон.xlsx` из корня проекта. Импорт принимает только этот формат: дата, Т1, Т2, тариф Т1, тариф Т2. Пустые строки, пропуски, неупорядоченные даты и нерастущие показания отклоняются до изменения БД.
