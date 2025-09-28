#!/bin/bash

# QuizMaker Backend - Server Setup Script
# Run this script on your Digital Ocean Ubuntu server to prepare for backend deployment

echo "🚀 Starting QuizMaker Backend server setup..."

# Check if running as root or with sudo
if [ "$EUID" -ne 0 ]; then
    echo "❌ This script must be run as root or with sudo"
    echo "Usage: sudo bash server-setup.sh"
    exit 1
fi

# Update system
echo "📦 Updating system packages..."
apt update && apt upgrade -y

# Install required packages
echo "📦 Installing required packages..."
apt install -y curl wget unzip software-properties-common apt-transport-https ca-certificates gnupg lsb-release

# Install Docker (if not already installed)
if ! command -v docker &> /dev/null; then
    echo "🐳 Installing Docker..."
    curl -fsSL https://get.docker.com -o get-docker.sh
    sh get-docker.sh
    rm get-docker.sh
    
    # Install Docker Compose plugin
    echo "🐳 Installing Docker Compose plugin..."
    apt-get install -y docker-compose-plugin
else
    echo "✅ Docker already installed"
fi

# Add deploy user to docker group (if exists)
if id "deploy" &>/dev/null; then
    usermod -aG docker deploy
    echo "✅ Added deploy user to docker group"
else
    echo "ℹ️  Deploy user not found, skipping docker group assignment"
fi

# Create backend deployment directory
echo "📁 Creating backend deployment directory..."
mkdir -p /var/www/quizmaker-backend
chown deploy:deploy /var/www/quizmaker-backend 2>/dev/null || echo "⚠️  Deploy user not found, using root ownership"

# Update Nginx configuration to include backend proxy
echo "🌐 Updating Nginx configuration for backend..."

# Backup existing configuration
cp /etc/nginx/sites-available/quizzence.com /etc/nginx/sites-available/quizzence.com.backup

# Create new configuration with backend proxy
cat > /etc/nginx/sites-available/quizzence.com << 'EOF'
server {
    listen 80;
    server_name quizzence.com www.quizzence.com;
    
    # Frontend (existing)
    location / {
        proxy_pass http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_cache_bypass $http_upgrade;
    }
    
    # Backend API
    location /api/ {
        proxy_pass http://localhost:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
        
        # CORS headers (if needed)
        add_header 'Access-Control-Allow-Origin' 'https://quizzence.com' always;
        add_header 'Access-Control-Allow-Methods' 'GET, POST, PUT, DELETE, OPTIONS' always;
        add_header 'Access-Control-Allow-Headers' 'Authorization, Content-Type, Accept, X-Requested-With, Origin' always;
        add_header 'Access-Control-Allow-Credentials' 'true' always;
        
        # Handle preflight requests
        if ($request_method = 'OPTIONS') {
            add_header 'Access-Control-Allow-Origin' 'https://quizzence.com' always;
            add_header 'Access-Control-Allow-Methods' 'GET, POST, PUT, DELETE, OPTIONS' always;
            add_header 'Access-Control-Allow-Headers' 'Authorization, Content-Type, Accept, X-Requested-With, Origin' always;
            add_header 'Access-Control-Allow-Credentials' 'true' always;
            add_header 'Access-Control-Max-Age' 1728000;
            add_header 'Content-Type' 'text/plain; charset=utf-8';
            add_header 'Content-Length' 0;
            return 204;
        }
    }
    
    # Backend actuator endpoints (health checks, etc.)
    location /actuator/ {
        proxy_pass http://localhost:8080/actuator/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # Restrict access to actuator endpoints (optional)
        # allow 127.0.0.1;
        # deny all;
    }
    
    # File upload size limits
    client_max_body_size 150M;
    
    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
}
EOF

# Test Nginx configuration
echo "🧪 Testing Nginx configuration..."
if nginx -t; then
    echo "✅ Nginx configuration is valid"
    systemctl reload nginx
    echo "✅ Nginx reloaded successfully"
else
    echo "❌ Nginx configuration is invalid, restoring backup"
    cp /etc/nginx/sites-available/quizzence.com.backup /etc/nginx/sites-available/quizzence.com
    exit 1
fi

# Note: Backend is bound to 127.0.0.1:8080, so no firewall rule needed
echo "🔥 Backend bound to localhost - no additional firewall rules needed"

# Create systemd service for monitoring (optional)
echo "📊 Creating monitoring service..."
cat > /etc/systemd/system/quizmaker-monitor.service << 'EOF'
[Unit]
Description=QuizMaker Backend Monitor
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
ExecStart=/bin/bash -c 'cd /var/www/quizmaker-backend && docker compose ps | grep -q "Up" || docker compose up -d'
User=root
Group=root
WorkingDirectory=/var/www/quizmaker-backend

[Install]
WantedBy=multi-user.target
EOF

# Create timer for the monitoring service
cat > /etc/systemd/system/quizmaker-monitor.timer << 'EOF'
[Unit]
Description=Run QuizMaker Backend Monitor every 5 minutes
Requires=quizmaker-monitor.service

[Timer]
OnCalendar=*:0/5
Persistent=true

[Install]
WantedBy=timers.target
EOF

# Enable and start the timer
systemctl daemon-reload
systemctl enable quizmaker-monitor.timer
systemctl start quizmaker-monitor.timer

# Note: Log rotation is handled by Docker's logging driver
# Application logs to stdout/stderr and Docker manages log rotation
echo "📝 Log rotation is handled by Docker logging driver"

# Create backup script
echo "💾 Creating backup script..."
cat > /usr/local/bin/backup-quizmaker.sh << 'EOF'
#!/bin/bash
# QuizMaker Backup Script

BACKUP_DIR="/var/backups/quizmaker"
DATE=$(date +%Y%m%d_%H%M%S)

# Create backup directory
mkdir -p "$BACKUP_DIR"

# Change to deployment directory
cd /var/www/quizmaker-backend

# Load environment variables
if [ -f .env ]; then
    set -a
    source .env
    set +a
fi

# Backup database using container environment
echo "Backing up database..."
docker compose exec -T mysql sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" quizmakerdb' > "$BACKUP_DIR/database_$DATE.sql"

# Backup uploads
echo "Backing up uploads..."
docker compose exec -T quizmaker-backend tar -czf - /app/uploads > "$BACKUP_DIR/uploads_$DATE.tar.gz"

# Keep only last 7 days of backups
find "$BACKUP_DIR" -name "*.sql" -mtime +7 -delete
find "$BACKUP_DIR" -name "*.tar.gz" -mtime +7 -delete

echo "Backup completed: $DATE"
EOF

chmod +x /usr/local/bin/backup-quizmaker.sh

# Add backup to crontab (daily at 2 AM)
(crontab -l 2>/dev/null; echo "0 2 * * * /usr/local/bin/backup-quizmaker.sh >> /var/log/quizmaker-backup.log 2>&1") | crontab -

echo "✅ Backend server setup completed!"
echo ""
echo "📋 Next steps:"
echo "1. The backend deployment directory is ready at: /var/www/quizmaker-backend"
echo "2. Nginx is configured to proxy /api/ requests to the backend"
echo "3. Firewall allows backend traffic on port 8080"
echo "4. Monitoring service will check backend health every 5 minutes"
echo "5. Daily backups are scheduled at 2 AM"
echo ""
echo "🔧 Manual steps required:"
echo "1. Set up environment variables in /var/www/quizmaker-backend/.env"
echo "2. Deploy the backend using CI/CD or manual deployment"
echo "3. Update SSL certificate to include backend endpoints if needed"
echo ""
echo "🎉 Backend server setup complete!"
