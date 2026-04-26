import os
import httpx
from typing import List

class PushNotificationService:
    def __init__(self):
        self.provider = os.getenv("PUSH_PROVIDER", "webpush")  # webpush, fcm, onesignal
        self.vapid_public_key = os.getenv("VAPID_PUBLIC_KEY", "")
        self.vapid_private_key = os.getenv("VAPID_PRIVATE_KEY", "")
        self.fcm_server_key = os.getenv("FCM_SERVER_KEY", "")

    async def send_webpush(self, subscription: dict, payload: dict):
        """Send Web Push notification using VAPID"""
        try:
            from pywebpush import webpush, WebPushException

            webpush(
                subscription_info=subscription,
                data=json.dumps(payload),
                vapid_private_key=self.vapid_private_key,
                vapid_claims={
                    "sub": "mailto:team@visionstudio.app"
                }
            )
            return True
        except Exception as e:
            print(f"WebPush failed: {e}")
            return False

    async def send_fcm(self, tokens: List[str], payload: dict):
        """Send via Firebase Cloud Messaging"""
        if not self.fcm_server_key:
            return False

        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    "https://fcm.googleapis.com/fcm/send",
                    headers={
                        "Authorization": f"key={self.fcm_server_key}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "registration_ids": tokens,
                        "notification": {
                            "title": payload.get("title"),
                            "body": payload.get("body"),
                            "icon": "/icons/icon-192x192.png",
                            "badge": "/icons/icon-72x72.png",
                            "click_action": payload.get("url", "/"),
                        },
                        "data": payload.get("data", {}),
                    }
                )
                return response.status_code == 200
        except Exception as e:
            print(f"FCM failed: {e}")
            return False

    async def notify_job_complete(self, user_id: str, job_type: str, result_url: str = None):
        """Send notification when job completes"""
        from app.database import async_session
        from sqlalchemy import select
        from app.models import Device

        async with async_session() as db:
            result = await db.execute(
                select(Device).where(
                    Device.user_id == user_id,
                    Device.push_enabled == True
                )
            )
            devices = result.scalars().all()

            type_labels = {
                "generation": "Image generation",
                "edit": "Image edit",
                "animation": "Animation",
            }

            payload = {
                "title": "VisionStudio AI",
                "body": f"Your {type_labels.get(job_type, 'creation')} is ready!",
                "url": f"/projects",
                "data": {
                    "jobType": job_type,
                    "resultUrl": result_url,
                }
            }

            for device in devices:
                if device.push_token:
                    # Try WebPush first
                    try:
                        subscription = json.loads(device.push_token)
                        await self.send_webpush(subscription, payload)
                    except:
                        # Fallback to FCM if token format is different
                        pass

import json
