#!/bin/bash

# QuizMaker Backend - Local Deployment Script
# Run this script locally to test deployment

echo "🚀 Starting QuizMaker Backend local deployment test..."

# Check if we're in the right directory
if [ ! -f "pom.xml" ]; then
    echo "❌ Error: Please run this script from the project root directory"
    exit 1
fi

# Check if .env file exists
if [ ! -f ".env" ]; then
    echo "❌ Error: .env file not found. Please create one based on env.example"
    exit 1
fi

# Load environment variables
set -a
source .env
set +a

# Validate required environment variables
required_vars=(
    "DB_USERNAME"
    "DB_PASSWORD" 
    "DB_ROOT_PASSWORD"
    "JWT_SECRET"
    "TOKEN_PEPPER_SECRET"
    "OPENAI_API_KEY"
    "APP_EMAIL_PROVIDER"
    "APP_EMAIL_FROM"
    "AWS_ACCESS_KEY_ID"
    "AWS_SECRET_ACCESS_KEY"
)

for var in "${required_vars[@]}"; do
    if [ -z "${!var}" ]; then
        echo "❌ Error: Required environment variable $var is not set"
        exit 1
    fi
done

echo "✅ Environment variables validated"

# Ensure clean working tree to avoid shipping stale/untracked sources
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    if [ -n "$(git status --porcelain)" ]; then
        echo "❌ Error: Working tree is dirty. Commit or stash changes before deployment."
        git status --short
        exit 1
    fi
else
    echo "⚠️  Warning: git not available; skipping clean working tree check"
fi

# Create deployment directory
echo "📁 Creating deployment directory..."
rm -rf deployment && mkdir -p deployment

# Build the application
echo "🔨 Building application..."
./mvnw clean package -DskipTests -B

# Check if build was successful
if ! ls target/QuizMaker-*.jar >/dev/null 2>&1; then
    echo "❌ Error: Build failed - JAR file not found"
    exit 1
fi

# Copy necessary files to deployment directory
echo "📦 Preparing deployment bundle..."
cp -r server/backend/* deployment/
cp pom.xml deployment/
cp -r src deployment/
cp -r target deployment/

# Navigate to deployment directory
cd deployment

# Test Docker build
echo "🐳 Testing Docker build..."
docker build -t quizmaker-backend:test .

# Test Docker Compose
echo "🐳 Testing Docker Compose..."
docker-compose up -d --build

# Wait for services to start
echo "⏳ Waiting for services to start..."
sleep 30

# Check if backend container is running
if docker ps | grep -q quizmaker-backend; then
    echo "✅ Backend container is running successfully!"
    
    # Wait a bit more for the application to fully start
    echo "⏳ Waiting for application to fully start..."
    sleep 30
    
    # Test health endpoint
    echo "🏥 Testing health endpoint..."
    if curl -f http://localhost:8080/actuator/health/readiness > /dev/null 2>&1; then
        echo "✅ Health check passed!"
        echo "🌐 Backend API should be available at: http://localhost:8080"
        echo "📊 Health endpoint: http://localhost:8080/actuator/health/readiness"
        echo "📚 API docs: http://localhost:8080/api/v1/docs/swagger-ui.html"
    else
        echo "⚠️  Health check failed, but container is running. Check logs:"
        echo "   docker-compose logs quizmaker-backend"
    fi
else
    echo "❌ Error: Backend container failed to start"
    echo "📋 Check logs with: docker-compose logs"
    exit 1
fi

# Check if MySQL container is running
if docker ps | grep -q mysql; then
    echo "✅ MySQL container is running successfully!"
else
    echo "❌ Error: MySQL container failed to start"
    echo "📋 Check logs with: docker-compose logs mysql"
fi

echo ""
echo "📋 To stop the application, run:"
echo "   docker-compose down"
echo ""
echo "📋 To view logs, run:"
echo "   docker-compose logs -f"
echo ""
echo "📋 To view backend logs only:"
echo "   docker-compose logs -f quizmaker-backend"

echo "🎉 Local deployment test completed!"
